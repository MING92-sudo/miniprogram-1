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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

/** token 中控：GET + 查询串； */
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

    @Test
    void transientNetworkErrorRetriesOnceThenSucceeds() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withSuccess(
                        "{\"code\":200,\"data\":{\"access_token\":\"retry.ok\",\"expires_in\":3599}}",
                        MediaType.APPLICATION_JSON));
        assertEquals("retry.ok", service.getToken());
        server.verify();
    }

    @Test
    void persistentNetworkErrorThrows2001AfterRetry() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withException(new java.io.IOException("connect timed out")));
        BizException e = assertThrows(BizException.class, service::getToken);
        assertEquals(2001, e.getCode());
        server.verify();
    }

    @Test
    void businessFailureNotRetried() {
        // 平台业务非 200（凭证/白名单）只请求一次，不重试
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withSuccess("{\"code\":10001,\"message\":\"认证失败\",\"data\":null}",
                        MediaType.APPLICATION_JSON));
        BizException e = assertThrows(BizException.class, service::getToken);
        assertEquals(2001, e.getCode());
        server.verify();
    }

    @Test
    void networkExceptionLogNeverContainsCredentials() {
        PlatformProperties props = new PlatformProperties();
        props.setAuthLoginUrl("https://auth.example.com/login");
        props.setUsername("reg-user");
        props.setKey("reg-key");
        props.setAppcode("reg-appcode");
        props.setSecret("reg-secret");
        RestTemplate broken = org.mockito.Mockito.mock(RestTemplate.class);
        org.mockito.Mockito.when(broken.getForEntity(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "I/O error on GET request for \"https://auth.example.com/login?username=reg-user"
                                + "&key=reg-key&appcode=reg-appcode&secret=reg-secret\": boom"));
        PlatformTokenService insecure = new PlatformTokenService(props, broken);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PlatformTokenService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(BizException.class, insecure::getToken);
            String logs = appender.list.stream().map(e -> e.getFormattedMessage()).reduce("", (a, b) -> a + b);
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("reg-user"), logs);
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("reg-key"), logs);
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("reg-appcode"), logs);
            org.junit.jupiter.api.Assertions.assertFalse(logs.contains("reg-secret"), logs);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
