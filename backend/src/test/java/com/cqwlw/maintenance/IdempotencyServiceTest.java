package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无响应的悬挂幂等记录（业务异常后未 commit）10 分钟过期放行——否则离线队列同 key 重试永远 422，任务永久卡死。 */
class IdempotencyServiceTest {

    private final IdempotencyKeyMapper mapper = mock(IdempotencyKeyMapper.class);
    private final IdempotencyService service = new IdempotencyService(mapper, TxTestSupport.noopTx());

    private IdempotencyKey row(String responseJson, java.time.LocalDateTime createdAt) {
        IdempotencyKey k = new IdempotencyKey();
        k.idemKey = "k1";
        k.responseJson = responseJson;
        k.createdAt = createdAt;
        return k;
    }

    @Test
    void commitRedactsSecretsWithoutMutatingOriginalResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "emp_1");
        result.put("initialPassword", "root-secret");
        result.put("password", "plain-secret");

        IdempotencyKey persisted = persist("secret-root", result);

        Map<String, Object> stored = JsonUtil.readMap(persisted.responseJson);
        assertEquals("emp_1", stored.get("id"));
        assertFalse(stored.containsKey("initialPassword"));
        assertFalse(stored.containsKey("password"));
        assertEquals("root-secret", result.get("initialPassword"));
        assertEquals("plain-secret", result.get("password"));
    }

    @Test
    void commitRedactsNestedSecretsInObjectsAndArrays() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("label", "保留");
        nested.put("password", "nested-secret");
        Map<String, Object> arrayItem = new LinkedHashMap<>();
        arrayItem.put("name", "保留项");
        arrayItem.put("initialPassword", "array-secret");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nested", nested);
        result.put("items", List.of(arrayItem));

        Map<String, Object> stored = JsonUtil.readMap(persist("secret-nested", result).responseJson);

        Map<?, ?> storedNested = (Map<?, ?>) stored.get("nested");
        assertEquals("保留", storedNested.get("label"));
        assertFalse(storedNested.containsKey("password"));
        Map<?, ?> storedItem = (Map<?, ?>) ((List<?>) stored.get("items")).get(0);
        assertEquals("保留项", storedItem.get("name"));
        assertFalse(storedItem.containsKey("initialPassword"));
    }

    @Test
    void replayedResultContainsNoPersistedSecrets() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "ca_1");
        result.put("initialPassword", "replay-secret");
        IdempotencyKey persisted = persist("secret-replay", result);
        when(mapper.selectById("secret-replay")).thenReturn(persisted);

        Map<String, Object> replayed = service.executeExternal("secret-replay", Map.of(),
                request("POST", "/test", null, "emp_1"), () -> {
                    throw new AssertionError("重放不得执行业务");
                });

        assertEquals("ca_1", replayed.get("id"));
        assertFalse(replayed.containsKey("initialPassword"));
    }

    @Test
    void completedSameFingerprintReplays() {
        AtomicInteger calls = new AtomicInteger();
        HttpServletRequest request = request("POST", "/rescues", null, "emp_1");
        Map<String, Object> body = Map.of("desc", "电梯困人");
        IdempotencyKey stored = completeFirst("same-fingerprint", body, request, calls);
        when(mapper.selectById("same-fingerprint")).thenReturn(stored);

        Map<String, Object> replay = run("same-fingerprint", body, request, calls);

        assertEquals(true, replay.get("ok"));
        assertEquals(1, calls.get());
    }

    @Test
    void differentRequestFingerprintConflictsWithoutExecuting() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("desc", "电梯困人");
        IdempotencyKey stored = completeFirst("fingerprint-conflict", body,
                request("POST", "/rescues", "token-a", "emp_1"), calls);
        when(mapper.selectById("fingerprint-conflict")).thenReturn(stored);
        List<HttpServletRequest> requests = List.of(
                request("PUT", "/rescues", "token-a", "emp_1"),
                request("POST", "/drills", "token-a", "emp_1"),
                request("POST", "/rescues", "token-b", "emp_1"),
                request("POST", "/rescues", "token-a", "emp_2"));

        for (HttpServletRequest changed : requests) {
            assertThrows(BizException.class,
                    () -> run("fingerprint-conflict", body, changed, calls));
        }
        assertThrows(BizException.class, () -> run("fingerprint-conflict", Map.of("desc", "不同"),
                request("POST", "/rescues", "token-a", "emp_1"), calls));
        assertEquals(1, calls.get());
    }

    @Test
    void canonicalMapOrderDoesNotChangeFingerprintButListOrderDoes() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("items", List.of(1, 2));
        HttpServletRequest request = request("POST", "/canonical", null, "emp_1");
        IdempotencyKey stored = completeFirst("canonical", first, request, calls);
        when(mapper.selectById("canonical")).thenReturn(stored);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("items", List.of(1, 2));
        reordered.put("a", 1);

        run("canonical", reordered, request, calls);

        assertEquals(1, calls.get());
        assertThrows(BizException.class, () -> run("canonical", Map.of("a", 1, "items", List.of(2, 1)),
                request, calls));
        assertEquals(1, calls.get());
    }

    @Test
    void legacyRecordFailsClosedWithoutReplayOrExecution() {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyKey completed = row("{\"ok\":true}", TimeUtil.now());
        completed.idemKey = "legacy-complete";
        when(mapper.selectById("legacy-complete")).thenReturn(completed);
        assertThrows(BizException.class, () -> run("legacy-complete", Map.of(),
                request("POST", "/legacy", null, "emp_1"), calls));

        IdempotencyKey pending = row(null, TimeUtil.now().minusMinutes(20));
        pending.idemKey = "legacy-pending";
        when(mapper.selectById("legacy-pending")).thenReturn(pending);
        assertThrows(BizException.class, () -> run("legacy-pending", Map.of(),
                request("POST", "/legacy", null, "emp_1"), calls));

        assertEquals(0, calls.get());
        verify(mapper, never()).deleteById(anyString());
    }

    @Test
    void freshPendingRecordRejectsDuplicate() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("id", 1);
        HttpServletRequest request = request("POST", "/pending", null, "emp_1");
        IdempotencyKey pending = completeFirst("fresh-pending", body, request, calls);
        pending.responseJson = null;
        pending.heartbeatAt = TimeUtil.now();
        when(mapper.selectById("fresh-pending")).thenReturn(pending);

        assertThrows(BizException.class, () -> run("fresh-pending", body, request, calls));

        assertEquals(1, calls.get());
    }

    @Test
    void stalePendingRecordCanBeTakenOver() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("id", 1);
        HttpServletRequest request = request("POST", "/pending", null, "emp_1");
        IdempotencyKey pending = completeFirst("stale-pending", body, request, calls);
        pending.responseJson = null;
        pending.heartbeatAt = TimeUtil.now().minusMinutes(11);
        when(mapper.selectById("stale-pending")).thenReturn(pending);
        when(mapper.takeover(anyString(), anyString(), any())).thenReturn(1);

        Map<String, Object> result = run("stale-pending", body, request, calls);

        assertEquals(true, result.get("ok"));
        assertEquals(2, calls.get());
    }

    @Test
    void completionFailurePropagatesAndNeverDeletesReservation() {
        AtomicInteger calls = new AtomicInteger();
        when(mapper.complete(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("completion failed"));

        assertThrows(IllegalStateException.class, () -> run("complete-failure", Map.of(),
                request("POST", "/completion", null, "emp_1"), calls));

        assertEquals(1, calls.get());
        verify(mapper, never()).release(anyString(), anyString());
        verify(mapper, never()).deleteById(anyString());
    }

    @Test
    void externalBusinessFailureReleasesOnlySameOwner() {
        when(mapper.release(anyString(), anyString())).thenReturn(1);
        HttpServletRequest request = request("POST", "/external-release", null, "emp_1");

        assertThrows(BizException.class, () -> service.executeExternal("external-release", Map.of(), request,
                () -> {
                    throw new BizException(422, "外部业务失败");
                }));

        ArgumentCaptor<IdempotencyKey> inserted = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(mapper).insert(inserted.capture());
        verify(mapper).release("external-release", inserted.getValue().ownerToken);
    }

    @Test
    void executeDbWithoutKeyRunsActionDirectly() {
        AtomicInteger calls = new AtomicInteger();

        Map<String, Object> result = service.executeDb(null, Map.of(),
                request("POST", "/no-key", null, "emp_1"), () -> {
                    calls.incrementAndGet();
                    return Map.of("ok", true);
                });

        assertEquals(true, result.get("ok"));
        assertEquals(1, calls.get());
    }

    @Test
    void executeDbReplayDoesNotCallBusiness() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> body = Map.of("id", 1);
        HttpServletRequest request = request("POST", "/db-replay", null, "emp_1");
        IdempotencyKey stored = completeFirst("db-replay", body, request, calls);
        when(mapper.selectById("db-replay")).thenReturn(stored);

        Map<String, Object> result = service.executeDb("db-replay", body, request, () -> {
            calls.incrementAndGet();
            return Map.of("ok", false);
        });

        assertEquals(true, result.get("ok"));
        assertEquals(1, calls.get());
    }

    @Test
    void executeDbBusinessFailurePropagates() {
        when(mapper.release(anyString(), anyString())).thenReturn(1);

        BizException failure = assertThrows(BizException.class, () -> service.executeDb("db-failure", Map.of(),
                request("POST", "/db-failure", null, "emp_1"), () -> {
                    throw new BizException(422, "业务失败");
                }));

        assertEquals(422, failure.getCode());
    }

    private IdempotencyKey completeFirst(String key, Map<String, Object> body,
                                         HttpServletRequest request, AtomicInteger calls) {
        when(mapper.complete(anyString(), anyString(), anyString())).thenReturn(1);
        run(key, body, request, calls);
        ArgumentCaptor<IdempotencyKey> inserted = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(mapper).insert(inserted.capture());
        IdempotencyKey stored = inserted.getValue();
        assertNotNull(stored.fingerprint);
        assertNotNull(stored.ownerToken);
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(mapper).complete(eq(key), eq(stored.ownerToken), response.capture());
        stored.responseJson = response.getValue();
        return stored;
    }

    private Map<String, Object> run(String key, Object body, HttpServletRequest request,
                                    AtomicInteger calls) {
        return service.executeExternal(key, body, request, () -> {
            calls.incrementAndGet();
            return new LinkedHashMap<>(Map.of("ok", true));
        });
    }

    private HttpServletRequest request(String method, String uri, String token, String principal) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getParameterMap()).thenReturn(token == null ? Map.of()
                : Map.of("token", new String[]{token}));
        when(request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE))
                .thenReturn(principal == null ? "ANONYMOUS" : "EMPLOYEE");
        when(request.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn(principal);
        return request;
    }

    private IdempotencyKey persist(String key, Map<String, Object> result) {
        when(mapper.complete(anyString(), anyString(), anyString())).thenReturn(1);
        service.executeExternal(key, Map.of(), request("POST", "/test", null, "emp_1"), () -> result);
        ArgumentCaptor<IdempotencyKey> inserted = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(mapper).insert(inserted.capture());
        IdempotencyKey persisted = inserted.getValue();
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(mapper).complete(eq(key), eq(persisted.ownerToken), response.capture());
        persisted.responseJson = response.getValue();
        return persisted;
    }
}
