package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.util.LogSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/** 日志脱敏：不打印完整 token/凭证。 */
@Service
public class PlatformTokenService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenService.class);

    private final PlatformProperties props;
    private final RestTemplate restTemplate;

    private volatile String cachedToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    public PlatformTokenService(PlatformProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    public boolean configured() {
        return notBlank(props.getAuthLoginUrl()) && notBlank(props.getUsername())
                && notBlank(props.getKey()) && notBlank(props.getAppcode()) && notBlank(props.getSecret());
    }

    public String getToken() {
        if (isCachedValid()) {
            return cachedToken;
        }
        synchronized (this) {
            if (isCachedValid()) {
                return cachedToken;
            }
            login();
            return cachedToken;
        }
    }

    public void invalidate() {
        synchronized (this) {
            cachedToken = null;
            cachedExpiry = Instant.EPOCH;
        }
    }

    private boolean isCachedValid() {
        return cachedToken != null && Instant.now().isBefore(cachedExpiry);
    }

    private void login() {
        if (!configured()) {
            throw new BizException(2001, "监管平台凭证未配置（REG_* 环境变量）");
        }
        // 凭证仅拼入请求 URL（平台 2.1 实测要求）；UriComponentsBuilder 负责编码，日志不得输出完整 URL
        String url = UriComponentsBuilder.fromHttpUrl(props.getAuthLoginUrl())
                .queryParam("username", props.getUsername())
                .queryParam("key", props.getKey())
                .queryParam("appcode", props.getAppcode())
                .queryParam("secret", props.getSecret())
                .build().encode().toUriString();
        // 平台业务非 200（凭证/白名单）不重试。
        BizException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
                Map<String, Object> body = MAPPER.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {
                });
                // code 兼容数字/字符串 "200"
                if (!"200".equals(String.valueOf(body.get("code")).trim())) {
                    log.warn("平台登录失败: code={}, message={}", body.get("code"), body.get("message"));
                    throw new BizException(2001, "监管平台 token 获取失败");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                cachedToken = String.valueOf(data.get("access_token"));
                long expiresIn = ((Number) data.get("expires_in")).longValue();
                cachedExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
                log.info("平台 token 已刷新，TTL={}s", expiresIn - 60);
                return;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.warn("平台登录请求异常(第{}次): {}", attempt, LogSanitizer.withoutQuery(e.getMessage()));
                last = new BizException(2001, "监管平台 token 获取失败");
            }
        }
        throw last;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
