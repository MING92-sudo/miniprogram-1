package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 本地文件访问签名：{@code HMAC-SHA256(key, fileId:expiry)} 的十六进制摘要，附带过期时间戳。
 *
 * <p>存在的原因：{@code GET /files/{id}} 不能靠 JWT 保护——小程序 {@code <image src>} 不会带
 * Authorization 头，而使用单位签字页是经 shareToken 匿名访问、同样要渲染签名图。因此改为
 * 所有下发给客户端的文件 URL 都携带该签名，读取时校验。
 *
 * <p>签名<b>带时效</b>（默认 1 小时）：URL 每次读时由
 * {@code FileStorageService.resolveUrl / resolveStoredUrl} 重新签发新鲜签名，客户端拿到的
 * 一定是未过期链接；过期旧链接失效，泄出的 URL 不会永久可读。
 *
 * <p>密钥复用已强制必填的 {@code app.jwtSecret}（缺失即启动失败），不新增配置项，
 * 也不存在默认密钥导致的 fail-open。
 */
@Service
public class FileAccessTokenService {

    /** 签名有效期（秒）；读时重签，故可取较短值 */
    static final long TTL_SECONDS = 3600;

    private final byte[] key;

    public FileAccessTokenService(AppProperties props) {
        String secret = props.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            // 与 JwtService 同口径：密钥缺失宁可启动失败，也不用默认值让签名可被伪造
            throw new IllegalStateException("文件访问签名密钥缺失：请配置 JWT_SECRET");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String fileId) {
        return sign(fileId, System.currentTimeMillis() / 1000 + TTL_SECONDS);
    }

    /** 指定过期时间戳签发（供测试构造过期/未来令牌） */
    public String sign(String fileId, long expiryEpochSeconds) {
        return expiryEpochSeconds + "." + hmac(fileId + ":" + expiryEpochSeconds);
    }

    /** 常量时间比较 + 过期校验；避免按字节比对泄露信息 */
    public boolean verify(String fileId, String token) {
        if (fileId == null || fileId.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (exp < System.currentTimeMillis() / 1000) {
            return false;
        }
        String sig = token.substring(dot + 1);
        return MessageDigest.isEqual(
                hmac(fileId + ":" + exp).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("文件访问签名计算失败", e);
        }
    }
}
