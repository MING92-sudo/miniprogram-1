package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.EvidenceTokenService;
import com.cqwlw.maintenance.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到取证令牌（docs/04 A.2）：HMAC 签名绑定工单/坐标/服务端时间/一次性随机数；
 * 篡改、过期、跨工单与重放均须拒绝。
 */
class EvidenceTokenServiceTest {

    private EvidenceTokenService service;
    private IdempotencyKeyMapper keyMapper;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-jwt-secret-value-0123456789");
        keyMapper = Mockito.mock(IdempotencyKeyMapper.class);
        // selectById 恒返回 null 模拟"nonce 未被占用"；占用路径由 IdempotencyService 单测覆盖
        Mockito.when(keyMapper.selectById(Mockito.anyString())).thenReturn(null);
        service = new EvidenceTokenService(props, new IdempotencyService(keyMapper));
    }

    @Test
    void issueBindsOrderAndCoordinates() {
        Map<String, Object> issued = service.issue("wo_1", 29.71921, 106.63352, 12L, 200);
        assertNotNull(issued.get("token"));
        assertEquals("wo_1", issued.get("orderId"));

        Map<String, Object> payload = service.verifyAndConsume((String) issued.get("token"), "wo_1");
        assertEquals("wo_1", payload.get("oid"));
        assertEquals(29.71921, (Double) payload.get("lat"), 1e-6);
        assertEquals(106.63352, (Double) payload.get("lng"), 1e-6);
        assertTrue(((Number) payload.get("st")).longValue() > 0L);
    }

    @Test
    void tamperedPayloadRejected() {
        String token = (String) service.issue("wo_1", 29.71921, 106.63352, 0L, 200).get("token");
        int dot = token.indexOf('.');
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"oid\":\"wo_1\",\"lat\":0,\"lng\":0,\"st\":1,\"exp\":9999999999,\"n\":\"x\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = forgedBody + token.substring(dot);
        BizException e = assertThrows(BizException.class, () -> service.verifyAndConsume(forged, "wo_1"));
        assertEquals(422, e.getCode());
    }

    @Test
    void missingTokenRejected() {
        BizException e = assertThrows(BizException.class, () -> service.verifyAndConsume(null, "wo_1"));
        assertEquals(422, e.getCode());
    }

    @Test
    void tokenBoundToOrder() {
        String token = (String) service.issue("wo_1", 29.71921, 106.63352, 0L, 200).get("token");
        BizException e = assertThrows(BizException.class, () -> service.verifyAndConsume(token, "wo_2"));
        assertEquals(422, e.getCode());
    }
}