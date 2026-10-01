package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private JwtService service() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-please-change-0123456789");
        return new JwtService(props);
    }

    @Test
    void issueAndVerify() {
        JwtService service = service();
        String token = service.issue("emp_1", "WORKER", "openid_x");
        Claims claims = service.verify(token);
        assertEquals("emp_1", claims.getSubject());
        assertEquals("WORKER", claims.get("role", String.class));
        assertEquals("openid_x", claims.get("openid", String.class));
    }

    @Test
    void invalidTokenThrows401() {
        JwtService service = service();
        BizException e = assertThrows(BizException.class, () -> service.verify("not-a-jwt"));
        assertEquals(401, e.getCode());
    }

@Test
    void missingSecretFailsFastAtStartup() {
        // 回归：签名密钥已无默认值（application.yml 移除 dev 默认串）。缺失必须启动失败，
        // 否则会以公开密钥签发 token，可伪造任意 role
        AppProperties props = new AppProperties();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JwtService(props));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void blankSecretFailsFastAtStartup() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("   ");
        assertThrows(IllegalStateException.class, () -> new JwtService(props));
    }

    @Test
    void shortSecretRejectedByHmac() {
        // 注入但不足 32 字节时 jjwt 抛 WeakKeyException，同样属启动即失败
        AppProperties props = new AppProperties();
        props.setJwtSecret("too-short");
assertThrows(Exception.class, () -> new JwtService(props));
    }
}
