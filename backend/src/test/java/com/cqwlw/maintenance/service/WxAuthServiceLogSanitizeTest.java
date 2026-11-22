package com.cqwlw.maintenance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 非 2xx 异常落日志前必须移除 URL 查询串，避免泄露密钥。 */
class WxAuthServiceLogSanitizeTest {

    @Test
    void stripsQueryCredentialsFromCauseMessage() {
        String msg = "Server returned HTTP response code: 400 for URL: "
                + "https://api.weixin.qq.com/sns/jscode2session?appid=wx123&secret=TOPSECRET&js_code=code123&grant_type=authorization_code";
        String out = WxAuthService.sanitizeCauseMessage(msg);
        assertFalse(out.contains("TOPSECRET"), "secret 不得落日志");
        assertFalse(out.contains("wx123"), "appid 不得落日志");
        assertFalse(out.contains("code123"), "js_code 不得落日志");
        assertTrue(out.contains("400"), "保留排障所需的 HTTP 码上下文");
    }

    @Test
    void keepsPlainMessageUnchanged() {
        assertEquals("connect timed out", WxAuthService.sanitizeCauseMessage("connect timed out"));
    }

    @Test
    void nullOrEmptyBecomesDash() {
        assertEquals("-", WxAuthService.sanitizeCauseMessage(null));
        assertEquals("-", WxAuthService.sanitizeCauseMessage(""));
    }
}