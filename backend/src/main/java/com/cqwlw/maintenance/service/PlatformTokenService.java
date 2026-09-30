package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    public PlatformTokenService(PlatformProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
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
        // TODO: GET props.getAuthLoginUrl()，查询串由 REG_* 环境变量拼装；日志必须脱敏完整凭证。
        throw new UnsupportedOperationException("平台登录换 token 尚未实现");
    }

    /** token 失效（平台返回 401/特定 code）时强制刷新。 */
    public void invalidate() {
        cachedToken = null;
        tokenExpireAt = 0;
    }
}
