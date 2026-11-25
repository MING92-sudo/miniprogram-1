package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 写接口幂等：所有写接口携带 X-Idempotency-Key（AGENTS §3）。 */
@Service
public class IdempotencyService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IdempotencyService.class);
    private static final Set<String> SECRET_RESPONSE_FIELDS = Set.of("initialPassword", "password");
    private static final ObjectMapper CANONICAL_MAPPER = JsonUtil.MAPPER.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final IdempotencyKeyMapper mapper;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<String, Execution> activeExternal = new ConcurrentHashMap<>();

    public IdempotencyService(IdempotencyKeyMapper mapper, TransactionTemplate transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public <T> T executeDb(String key, Object body, HttpServletRequest request, Supplier<T> action) {
        if (key == null || key.isEmpty()) {
            return action.get();
        }
        return transactions.execute(status -> executeWithLease(key, body, request, action));
    }

    public <T> T executeExternal(String key, Object body, HttpServletRequest request, Supplier<T> action) {
        if (key == null || key.isEmpty()) {
            return action.get();
        }
        Execution execution = transactions.execute(status -> reserve(key, body, request));
        if (execution.replayJson != null) {
            @SuppressWarnings("unchecked")
            T replay = (T) JsonUtil.readMap(execution.replayJson);
            return replay;
        }
        activeExternal.put(execution.ownerToken, execution);
        try {
            T result;
            try {
                result = action.get();
            } catch (RuntimeException | Error e) {
                try {
                    transactions.executeWithoutResult(status -> release(execution));
                } catch (RuntimeException | Error releaseFailure) {
                    e.addSuppressed(releaseFailure);
                }
                throw e;
            }
            try {
                transactions.executeWithoutResult(status -> complete(execution, result));
            } catch (RuntimeException | Error completeFailure) {
                // 业务侧（可能含监管平台写）已成功而 complete 落库失败：键停留态会被 takeover 重放、向平台重复登记。
                // 补偿清空 ownerToken 使后续同键请求走「需人工核对」422 分支；补偿自身失败只记日志，原异常照常抛出。
                try {
                    transactions.executeWithoutResult(status ->
                            mapper.markNeedsReview(execution.key, execution.ownerToken));
                } catch (RuntimeException | Error compensateFailure) {
                    log.error("幂等完成写失败后补偿置核对态失败, key={}", execution.key);
                }
                throw completeFailure;
            }
            return result;
        } finally {
            activeExternal.remove(execution.ownerToken, execution);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void heartbeatActive() {
        for (Execution execution : activeExternal.values()) {
            mapper.heartbeat(execution.key, execution.ownerToken, TimeUtil.now());
        }
    }

    private <T> T executeWithLease(String key, Object body, HttpServletRequest request, Supplier<T> action) {
        if (key == null || key.isEmpty()) {
            return action.get();
        }
        Execution execution = reserve(key, body, request);
        if (execution.replayJson != null) {
            @SuppressWarnings("unchecked")
            T replay = (T) JsonUtil.readMap(execution.replayJson);
            return replay;
        }
        T result;
        try {
            result = action.get();
        } catch (RuntimeException | Error e) {
            release(execution);
            throw e;
        }
        complete(execution, result);
        return result;
    }

    private Execution reserve(String key, Object body, HttpServletRequest request) {
        String fingerprint = fingerprint(body, request);
        LocalDateTime now = TimeUtil.now();
        IdempotencyKey exist = mapper.selectById(key);
        if (exist != null) {
            if (exist.fingerprint == null || exist.ownerToken == null || exist.heartbeatAt == null) {
                throw new BizException(422, "历史幂等记录需人工核对");
            }
            if (!exist.fingerprint.equals(fingerprint)) {
                throw new BizException(422, "幂等键已用于其他请求");
            }
            if (exist.responseJson != null && !exist.responseJson.isEmpty()) {
                return new Execution(key, null, exist.responseJson);
            }
            if (ChronoUnit.MINUTES.between(exist.heartbeatAt, now) < 10) {
                throw new BizException(422, "相同请求正在处理，请勿重复提交");
            }
            if (mapper.takeover(key, exist.ownerToken, now.minusMinutes(10)) != 1) {
                throw new BizException(422, "相同请求正在处理，请勿重复提交");
            }
        }
        IdempotencyKey row = new IdempotencyKey();
        row.idemKey = key;
        row.path = request.getMethod() + " " + request.getRequestURI();
        row.fingerprint = fingerprint;
        row.ownerToken = UUID.randomUUID().toString();
        row.heartbeatAt = now;
        row.createdAt = now;
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(422, "相同请求正在处理，请勿重复提交");
        }
        return new Execution(key, row.ownerToken, null);
    }

    private void complete(Execution execution, Object result) {
        int updated = mapper.complete(execution.key, execution.ownerToken, sanitizedResponseJson(result));
        if (updated != 1) {
            throw new IllegalStateException("幂等完成写失败");
        }
    }

    private void release(Execution execution) {
        if (execution.ownerToken == null) {
            return;
        }
        if (mapper.release(execution.key, execution.ownerToken) != 1) {
            throw new IllegalStateException("幂等释放失败");
        }
    }

    private static String fingerprint(Object body, HttpServletRequest request) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("method", request.getMethod());
        parts.put("uri", request.getRequestURI());
        parts.put("query", canonicalQuery(request));
        parts.put("body", body);
        parts.put("principal", principal(request));
        try {
            byte[] canonical = CANONICAL_MAPPER.writeValueAsBytes(parts);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("幂等请求指纹计算失败", e);
        }
    }

    private static Map<String, List<String>> canonicalQuery(HttpServletRequest request) {
        Map<String, List<String>> canonical = new TreeMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values == null) {
                canonical.put(name, List.of());
                return;
            }
            String[] sorted = values.clone();
            Arrays.sort(sorted);
            canonical.put(name, Arrays.asList(sorted));
        });
        return canonical;
    }

    private static String principal(HttpServletRequest request) {
        Object type = request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE);
        Object employee = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        Object unit = request.getAttribute(AuthInterceptor.ATTR_UNIT_ID);
        Object id = employee != null ? employee : unit;
        return String.valueOf(type == null ? "ANONYMOUS" : type) + ":" + String.valueOf(id);
    }

    private static class Execution {
        private final String key;
        private final String ownerToken;
        private final String replayJson;

        private Execution(String key, String ownerToken, String replayJson) {
            this.key = key;
            this.ownerToken = ownerToken;
            this.replayJson = replayJson;
        }
    }

    private static String sanitizedResponseJson(Object result) {
        JsonNode tree = JsonUtil.MAPPER.valueToTree(result);
        redactSecrets(tree);
        return JsonUtil.write(tree);
    }

    private static void redactSecrets(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(SECRET_RESPONSE_FIELDS);
            object.forEach(IdempotencyService::redactSecrets);
        } else if (node != null && node.isArray()) {
            node.forEach(IdempotencyService::redactSecrets);
        }
    }

}
