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

/** 日志严禁打印完整 token（AGENTS §2.4）。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireHours;

    public JwtService(AppProperties props) {
        String secret = props.getJwtSecret();
        // 签名密钥无默认值（application.yml 已移除 dev 默认串）：缺失即启动失败，否则会以公开密钥签发 token，任何人可伪造 role=SYS_ADMIN（AGENTS §2.1）
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 签名密钥未配置：请注入环境变量 JWT_SECRET（建议 32 字节以上强随机串），"
                            + "禁止使用默认值——默认值一旦入库即等同公开密钥");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = props.getJwtExpireHours();
    }

    public String issue(String employeeId, String role, String openid, String client, String sessionId) {
        return issue(employeeId, role, openid, client, sessionId, 0);
    }

    public String issue(String employeeId, String role, String openid, String client, String sessionId, int scopeVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("client", client);
        claims.put("sid", sessionId);
        claims.put("sv", scopeVersion);
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

    public String issueUnit(String useUnitId, String client) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "UNIT_ADMIN");
        claims.put("client", client);
        claims.put("ut", "1");
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(useUnitId)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireHours * 3600)))
                .signWith(key)
                .compact();
    }

    public String issueClient(String accountId, String role, String useUnitId, String openid,
                              String sessionId, int scopeVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("ut", "2");
        claims.put("role", role);
        claims.put("useUnitId", useUnitId);
        claims.put("sid", sessionId);
        claims.put("sv", scopeVersion);
        claims.put("client", "mp");
        if (openid != null) {
            claims.put("openid", openid);
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(accountId)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireHours * 3600)))
                .signWith(key)
                .compact();
    }

    /** 仅用于 /auth/client/select-unit 换取正式客户令牌，不能访问业务端点（白名单不含）。 */
    public String issueSelection(String phone) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sel", "1");
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(phone)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(5 * 60)))
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
