package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 自建 JWT：签发与校验（云托管免鉴权 openid → 本服务 JWT，AGENTS/README 部署约定）。
 * 日志严禁打印完整 token（AGENTS §2.4）。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireHours;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expireHours = props.getJwtExpireHours();
    }

    public String issue(String employeeId, String role, String openid, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("sid", sessionId);
        if (openid != null) {
            claims.put("openid", openid);
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(employeeId)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireHours * 3600)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 claims；无效/过期抛 401（前端统一清登录态） */
    public Claims verify(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
    }
}
