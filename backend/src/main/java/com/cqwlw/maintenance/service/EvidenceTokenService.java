package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签到取证令牌：拍照**之前**由服务端签发，把工单号、已通过地理围栏校验的坐标、服务端时间
 * 与一次性随机数绑定为签名令牌。水印相机只渲染令牌内的值；提交签到时后端验签、一次性核销，
 * 并采用令牌内的坐标与服务端时间落库——使照片水印与最终维保记录的时间/地点均为服务端
 * 可举证值，而不是客户端自填（docs/04 A.2）。
 *
 * <p>一次性核销复用 {@link IdempotencyService}：其 begin() 语义即「插入即占用，重复占用抛 422」，
 * 无需新增表或实体。令牌密钥复用 app.jwt-secret 并追加上下文串做域隔离，避免改动鉴权模块。
 */
@Service
public class EvidenceTokenService {

    private static final String HMAC_ALG = "HmacSHA256";
    /** 域隔离上下文串：使本模块的签名密钥不等于 JWT 签名密钥，避免跨用途复用 */
    private static final String CONTEXT = "|evidence-token-v1";
    private static final long TTL_SECONDS = 900L;

    private final AppProperties props;
    private final IdempotencyService idempotencyService;

    public EvidenceTokenService(AppProperties props, IdempotencyService idempotencyService) {
        this.props = props;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 签发取证令牌。调用方须已通过地理围栏与工单状态校验。
     *
     * @return token、签名载荷各字段（供水印渲染）与人类可读时间文本
     */
    public Map<String, Object> issue(String orderId, double lat, double lng, long distanceMeters, int thresholdM) {
        String nonce = java.util.UUID.randomUUID().toString();
        long issuedSec = System.currentTimeMillis() / 1000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oid", orderId);
        payload.put("lat", round6(lat));
        payload.put("lng", round6(lng));
        payload.put("st", issuedSec);
        payload.put("exp", issuedSec + TTL_SECONDS);
        payload.put("n", nonce);
        String body = JsonUtil.write(payload);
        String token = enc(body) + "." + hmac(body);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("orderId", orderId);
        out.put("issuedAt", TimeUtil.format(TimeUtil.now()));
        out.put("issuedAtText", TimeUtil.format(TimeUtil.now()));
        out.put("latitude", round6(lat));
        out.put("longitude", round6(lng));
        out.put("latText", String.format("%.5f", lat));
        out.put("lngText", String.format("%.5f", lng));
        out.put("distanceText", distanceMeters < 0 ? "" : String.valueOf(distanceMeters));
        out.put("thresholdText", String.valueOf(thresholdM));
        out.put("expiresInSeconds", TTL_SECONDS);
        return out;
    }

    /**
     * 验签 + 一次性核销。篡改、过期或已使用均拒绝。
     *
     * @return 载荷（含服务端时间与已校验坐标）
     */
    public Map<String, Object> verifyAndConsume(String token, String expectOrderId) {
        if (token == null || token.isBlank()) {
            throw new BizException(422, "缺少取证令牌，请重新获取定位后再签到");
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            throw new BizException(422, "取证令牌格式无效");
        }
        String body;
        Map<String, Object> payload;
        try {
            body = dec(token.substring(0, dot));
            payload = JsonUtil.readMap(body);
        } catch (Exception e) {
            throw new BizException(422, "取证令牌解析失败");
        }
        if (payload == null) {
            throw new BizException(422, "取证令牌解析失败");
        }
        String expect = hmac(body);
        String actual = token.substring(dot + 1);
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(422, "取证令牌签名校验失败");
        }
        long exp = num(payload.get("exp"));
        if (exp <= 0 || System.currentTimeMillis() / 1000L > exp) {
            throw new BizException(422, "取证令牌已过期，请重新获取定位");
        }
        String oid = str(payload.get("oid"));
        if (expectOrderId != null && !expectOrderId.equals(oid)) {
            throw new BizException(422, "取证令牌与工单不匹配");
        }
        // 一次性核销：首次 begin() 占用该 nonce，重复占用由 IdempotencyService 抛 422
        idempotencyService.begin("evidence:" + str(payload.get("n")), "/evidence");
        return payload;
    }

    /** 取令牌内的坐标（已通过围栏校验）；缺失返回 null */
    public static Double latOf(Map<String, Object> payload) {
        return payload == null ? null : numOrNull(payload.get("lat"));
    }

    public static Double lngOf(Map<String, Object> payload) {
        return payload == null ? null : numOrNull(payload.get("lng"));
    }

    private static Double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        Double d = numOrNull(o);
        return d == null ? -1L : d.longValue();
    }

    private static Double numOrNull(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec((props.getJwtSecret() + CONTEXT).getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("取证令牌签名失败", e);
        }
    }

    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
}