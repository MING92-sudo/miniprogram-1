package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 从必填 JWT_SECRET 派生独立文件签名密钥。 */
@Service
public class FileAccessTokenService {

    /** 签名有效期（秒）；读时重签，故可取较短值 */
    static final long TTL_SECONDS = 3600;

    /** 密钥域隔离串。 */
    private static final String CONTEXT = "|file-access-v1";

    private final byte[] key;

    public FileAccessTokenService(AppProperties props) {
        String secret = props.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            // 与 JwtService 同口径：密钥缺失宁可启动失败，也不用默认值让签名可被伪造
            throw new IllegalStateException("文件访问签名密钥缺失：请配置 JWT_SECRET");
        }
        this.key = (secret + CONTEXT).getBytes(StandardCharsets.UTF_8);
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
