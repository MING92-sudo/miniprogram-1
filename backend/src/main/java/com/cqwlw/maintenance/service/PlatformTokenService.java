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
     * TODO 按平台 V1.5 规范实现 login 表单参数（注意文档中 scret 拼写）与返回字段解析。
     */
    public synchronized String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        // TODO: POST props.getAuthLoginUrl()，表单 x-www-form-urlencoded
        throw new UnsupportedOperationException("平台登录换 token 尚未实现");
    }

    /** token 失效（平台返回 401/特定 code）时强制刷新。 */
    public void invalidate() {
        cachedToken = null;
        tokenExpireAt = 0;
    }
}
