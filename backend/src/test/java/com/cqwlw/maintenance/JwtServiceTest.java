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
    void defaultDevSecretMeetsHmacSha256Minimum() {
        // 回归：容器默认密钥必须 ≥32 字节，否则 Keys.hmacShaKeyFor 启动即抛 WeakKeyException
        String dev = "dev-only-secret-change-me-32bytes-minimum-0123456789";
        org.junit.jupiter.api.Assertions.assertTrue(dev.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32);
    }
}
