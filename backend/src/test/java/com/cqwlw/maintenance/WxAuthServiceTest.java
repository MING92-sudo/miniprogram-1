package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.service.WxAuthService;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 微信 code → openid（jscode2session，P0 串号修复 docs/04 A.1）：
 * ① 必须用 code 换取真实 openid（不同 code → 不同 openid，回归"全员同一 openid"）；
 * ② 未配置 WX_APPID/WX_APPSECRET 直接失败，绝不回退 dev_openid；
 * ③ errcode 映射为可区分的错误码：code 类问题=422（业务校验失败，前端不会清登录态；
 *    若用 401，刚登录完的 bind-wechat 会触发前端 handleUnauthorized 误登出）/ 配置与微信侧异常=2001。
 */
class WxAuthServiceTest {

    private MockRestServiceServer server;
    private AppProperties props;
    private WxAuthService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        props = new AppProperties();
        props.setWxAppid("wxappid123");
        props.setWxAppsecret("wxsecret456");
        service = new WxAuthService(props, restTemplate);
    }

    private void expectSuccess(String json) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void codeExchangedForRealOpenidWithAppCredentials() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("appid", "wxappid123"))
                .andExpect(queryParam("secret", "wxsecret456"))
                .andExpect(queryParam("js_code", "code_A"))
                .andExpect(queryParam("grant_type", "authorization_code"))
                .andRespond(withSuccess("{\"openid\":\"openid_A\",\"session_key\":\"sk\",\"unionid\":\"u\"}",
                        MediaType.APPLICATION_JSON));
        assertEquals("openid_A", service.openidFromCode("code_A"));
        server.verify();
    }

    @Test
    void distinctCodesYieldDistinctOpenids() {
        // P0 回归点：两个员工的 code 必须换到两个不同 openid
        expectSuccess("{\"openid\":\"openid_A\"}");
        expectSuccess("{\"openid\":\"openid_B\"}");
        assertEquals("openid_A", service.openidFromCode("code_A"));
        assertEquals("openid_B", service.openidFromCode("code_B"));
        server.verify();
    }

    @Test
    void invalidCodeMapsTo422() {
        expectSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code, rid: abc\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("bad_code"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("重新进入小程序登录"));
    }

    @Test
    void alreadyUsedCodeMapsTo422() {
        expectSuccess("{\"errcode\":40163,\"errmsg\":\"code been used\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("used_code"));
        assertEquals(422, e.getCode());
    }

    @Test
    void frequentCodeCallMapsTo422() {
        expectSuccess("{\"errcode\":45011,\"errmsg\":\"api minute-quota reach limit\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("code_A"));
        assertEquals(422, e.getCode());
    }

    @Test
    void unknownErrcodeMapsTo422WithErrcodeInMessage() {
        expectSuccess("{\"errcode\":40226,\"errmsg\":\"high risk user\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("code_A"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("40226"));
    }

    @Test
    void wechatSystemBusyMapsTo2001() {
        expectSuccess("{\"errcode\":-1,\"errmsg\":\"system error\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("code_A"));
        assertEquals(2001, e.getCode());
    }

    @Test
    void invalidAppSecretMapsTo2001() {
        expectSuccess("{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}");
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("code_A"));
        assertEquals(2001, e.getCode());
    }

    @Test
    void missingCredentialsFailClosedWithoutHttpCall() {
        WxAuthService unconfigured = new WxAuthService(new AppProperties(), new RestTemplate());
        BizException e = assertThrows(BizException.class, () -> unconfigured.openidFromCode("code_A"));
        assertEquals(2001, e.getCode());
        assertTrue(e.getMessage().contains("WX_APPID"));
    }

    @Test
    void blankCodeRejectedWithoutHttpCall() {
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("   "));
        assertEquals(422, e.getCode());
        server.verify(); // 未发出任何请求
    }

    @Test
    void httpErrorMapsTo2001() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withServerError());
        BizException e = assertThrows(BizException.class, () -> service.openidFromCode("code_A"));
        assertEquals(2001, e.getCode());
    }
}
