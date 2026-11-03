package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.service.WxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 微信登录 code → openid：code 缺失/无效属客户端可重试（422），凭证未配置或微信侧故障属服务端（2001）。
 * 422 而非 401 是硬约束——前端 request.js 对 401 会清登录态，绑定刚登录完就会被误登出。
 */
class WxAuthServiceTest {

    private static AppProperties props(String appid, String secret) {
        AppProperties p = new AppProperties();
        p.setWxAppid(appid);
        p.setWxAppsecret(secret);
        p.setJwtSecret("unit-test-secret-0123456789abcdef");
        return p;
    }

    private static WxAuthService service(AppProperties p, RestTemplate rest) {
        return new WxAuthService(p, rest);
    }

    @Test
    void blankCodeIsClientError() {
        BizException e = assertThrows(BizException.class,
                () -> service(props("wxapp", "wxsecret"), new RestTemplate()).openidFromCode("  "));
        assertEquals(422, e.getCode());
    }

    @Test
    void missingCredentialsIsServerError() {
        BizException e = assertThrows(BizException.class,
                () -> service(props("", ""), new RestTemplate()).openidFromCode("code_1"));
        assertEquals(2001, e.getCode());
    }

    @Test
    void returnsOpenidFromWechatResponse() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"openid\":\"openid_1\",\"session_key\":\"sk\"}", HttpStatus.OK));
        assertEquals("openid_1", service(props("wxapp", "wxsecret"), rest).openidFromCode("code_1"));
    }

    @Test
    void expiredCodeIsClientError() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":40029,\"errmsg\":\"invalid code\"}", HttpStatus.OK));
        BizException e = assertThrows(BizException.class,
                () -> service(props("wxapp", "wxsecret"), rest).openidFromCode("code_1"));
        assertEquals(422, e.getCode());
    }

    @Test
    void badAppSecretIsServerError() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}", HttpStatus.OK));
        BizException e = assertThrows(BizException.class,
                () -> service(props("wxapp", "wxsecret"), rest).openidFromCode("code_1"));
        assertEquals(2001, e.getCode());
    }
}
