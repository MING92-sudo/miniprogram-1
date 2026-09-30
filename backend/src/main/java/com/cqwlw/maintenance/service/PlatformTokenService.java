package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 监管平台 token 中控：
 * 负责用 REG_USERNAME / REG_KEY / REG_APPCODE / REG_SECRET
 * 调用平台 authService/login 换取 token，并在内存中缓存复用。
 */
@Service
public class PlatformTokenService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenService.class);

    private final PlatformProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    public PlatformTokenService(PlatformProperties props, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取可用 token，过期前自动刷新。
     * TODO 按实测约定实现：2.1 登录为 GET + URL 查询串（docs/04 B.1 / docs/07），
     * token 为 JWT，缓存 TTL = expires_in - 60s；401 时 invalidate() 后重登并重试 1 次。
     */
    public synchronized String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        String url = UriComponentsBuilder.fromHttpUrl(props.getAuthLoginUrl())
                .queryParam("username", props.getUsername())
                .queryParam("key", props.getKey())
                .queryParam("appcode", props.getAppcode())
                .queryParam("secret", props.getSecret())
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            String code = root.path("code").asText();
            String token = root.path("token").asText("");
            long expiresIn = root.path("expires_in").asLong(0);
            if (!"200".equals(code) || token.isBlank()) {
                throw new BusinessException(2001, "监管平台 token 获取失败");
            }
            cachedToken = token;
            tokenExpireAt = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;
            return cachedToken;
        } catch (BusinessException e) {
            invalidate();
            throw e;
        } catch (Exception e) {
            log.warn("监管平台 token 获取失败");
            invalidate();
            throw new BusinessException(2001, "监管平台 token 获取失败");
        }
    }

    /** token 失效（平台返回 401/特定 code）时强制刷新。 */
    public void invalidate() {
        cachedToken = null;
        tokenExpireAt = 0;
    }
}
