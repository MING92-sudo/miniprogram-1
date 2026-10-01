package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.service.PlatformTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * token 中控：GET + 查询串；code 兼容数字/字符串 "200"；
 * TTL = expires_in − 60s；401 失效清缓存重登重试 1 次由 PlatformClient 触发。
 */
class PlatformTokenServiceTest {

    private PlatformTokenService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        PlatformProperties props = new PlatformProperties();
        props.setAuthLoginUrl("https://auth.example.com/login");
        props.setUsername("u");
        props.setKey("k");
        props.setAppcode("a");
        props.setSecret("s");
        service = new PlatformTokenService(props, restTemplate);
    }

    @Test
    void loginWithNumericCodeAndCaches() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("username", "u"))
                .andExpect(queryParam("appcode", "a"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"data\":{\"access_token\":\"jwt.abc\",\"expires_in\":3599}}",
                        MediaType.APPLICATION_JSON));
        assertEquals("jwt.abc", service.getToken());
        // 缓存命中：不再发起第二次登录
        assertEquals("jwt.abc", service.getToken());
        server.verify();
    }

    @Test
    void stringCode200AlsoAccepted() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withSuccess(
                        "{\"code\":\"200\",\"message\":\"ok\",\"data\":{\"access_token\":\"t2\",\"expires_in\":3599}}",
                        MediaType.APPLICATION_JSON));
        assertEquals("t2", service.getToken());
        assertTrue(service.configured());
    }

    @Test
    void businessFailureThrows2001() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withSuccess("{\"code\":500,\"message\":\"操作失败!\",\"data\":null}",
                        MediaType.APPLICATION_JSON));
        BizException e = assertThrows(BizException.class, service::getToken);
        assertEquals(2001, e.getCode());
    }

    @Test
    void missingCredentialsThrows2001() {
        PlatformTokenService empty = new PlatformTokenService(new PlatformProperties(), new RestTemplate());
        BizException e = assertThrows(BizException.class, empty::getToken);
        assertEquals(2001, e.getCode());
    }
}
