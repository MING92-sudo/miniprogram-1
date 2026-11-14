package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.config.AppProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JWT sv claim：发证携带 scopeVersion，verify 可读回。 */
class JwtServiceSvTest {

    private JwtService service() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("test-secret-key-for-jwt-sv-claim-0123456789");
        return new JwtService(props);
    }

    @Test
    void issueCarriesScopeVersion() {
        JwtService jwt = service();
        String token = jwt.issue("emp_1", "WORKER", null, "mp", "sess_1", 7);
        Claims claims = jwt.verify(token);
        assertEquals("emp_1", claims.getSubject());
        assertEquals(7, ((Number) claims.get("sv")).intValue());
    }

    @Test
    void zeroScopeVersionIsCarried() {
        JwtService jwt = service();
        Claims claims = jwt.verify(jwt.issue("emp_1", "ADMIN", null, "admin", "sess_1", 0));
        assertEquals(0, ((Number) claims.get("sv")).intValue());
    }
}