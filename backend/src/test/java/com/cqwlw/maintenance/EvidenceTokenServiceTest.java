package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.EvidenceTokenService;
import com.cqwlw.maintenance.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到取证令牌（docs/04 A.2）：HMAC 签名绑定工单/检查项/坐标/服务端时间/一次性随机数；
 * 篡改、过期、跨工单、跨检查项均拒绝。签到令牌一次性核销，检查项拍照令牌只验签
 * （一次性由检查项绑定保证），以支持"提交后超时重试"。
 */
class EvidenceTokenServiceTest {

    private EvidenceTokenService service;
    /** 模拟 idempotency_key 表：已占用的 key 能被 selectById 查到，从而使 begin() 判定重放 */
    private final Set<String> occupied = new HashSet<>();

    @BeforeEach
    void setUp() {
        occupied.clear();
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-jwt-secret-value-0123456789");
        IdempotencyKeyMapper keyMapper = Mockito.mock(IdempotencyKeyMapper.class);
        Mockito.when(keyMapper.selectById(Mockito.anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            if (!occupied.contains(k)) {
                return null;
            }
            // 已占用的行：createdAt 为刚才占用时刻（真实库由 begin() 写入），
            // 不能留空——留空会被 IdempotencyService 视为遗留行而回收
            IdempotencyKey row = new IdempotencyKey();
            row.idemKey = k;
            row.createdAt = com.cqwlw.maintenance.util.TimeUtil.now();
            return row;
        });
        Mockito.when(keyMapper.insert(Mockito.any(IdempotencyKey.class))).thenAnswer(inv -> {
            occupied.add(((IdempotencyKey) inv.getArgument(0)).idemKey);
            return 1;
        });
        Mockito.when(keyMapper.updateById(Mockito.any(IdempotencyKey.class))).thenReturn(1);
        service = new EvidenceTokenService(props, new IdempotencyService(keyMapper));
    }

    @Test
    void issueBindsOrderAndCoordinates() {
        Map<String, Object> issued = service.issue("wo_1", 29.71921, 106.63352, 12L, 200);
        assertNotNull(issued.get("token"));

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

    @Test
    void checkinTokenIsSingleUse() {
        String token = (String) service.issue("wo_1", 29.71921, 106.63352, 0L, 200).get("token");
        assertNotNull(service.verifyAndConsume(token, "wo_1"));
        BizException replay = assertThrows(BizException.class,
                () -> service.verifyAndConsume(token, "wo_1"));
        assertEquals(422, replay.getCode());
    }

    @Test
    void shotTokenBindsItem() {
        Map<String, Object> issued = service.issue("wo_1", "ci_A-1-06", 29.71921, 106.63352,
                3L, 200, EvidenceTokenService.TTL_SHOT_SECONDS);
        String token = (String) issued.get("token");
        assertEquals("ci_A-1-06", service.verifyOnly(token, "wo_1", "ci_A-1-06").get("iid"));
        BizException wrongItem = assertThrows(BizException.class,
                () -> service.verifyOnly(token, "wo_1", "ci_A-1-07"));
        assertEquals(422, wrongItem.getCode());
    }

    @Test
    void verifyOnlyIsIdempotentSoResubmitSucceeds() {
        // 回归：检查项拍照令牌走 verifyOnly（不核销），"提交后网络超时→用户重试"必须能成功；
        // 若改为核销，重试会被已消费 nonce 永久 422 拒绝
        String token = (String) service.issue("wo_1", "ci_A-1-06", 29.71921, 106.63352,
                0L, 200, EvidenceTokenService.TTL_SHOT_SECONDS).get("token");
        assertNotNull(service.verifyOnly(token, "wo_1", "ci_A-1-06"));
        assertNotNull(service.verifyOnly(token, "wo_1", "ci_A-1-06"));
        assertNotNull(service.verifyOnly(token, "wo_1", "ci_A-1-06"));
    }

    @Test
    void signTokenBindsKindAndRole() {
        Map<String, Object> issued = service.issueSign("wo_1", "PRINCIPAL",
                EvidenceTokenService.TTL_SHOT_SECONDS);
        String token = (String) issued.get("token");
        Map<String, Object> payload = service.verifyOnly(token, "wo_1", null,
                EvidenceTokenService.KIND_SIGN, "PRINCIPAL");
        assertEquals("sign", payload.get("kind"));
        assertEquals("PRINCIPAL", payload.get("role"));
    }

    @Test
    void signTokenRejectsWrongRoleAndWrongKind() {
        String principal = (String) service.issueSign("wo_1", "PRINCIPAL",
                EvidenceTokenService.TTL_SHOT_SECONDS).get("token");
        BizException wrongRole = assertThrows(BizException.class,
                () -> service.verifyOnly(principal, "wo_1", null,
                        EvidenceTokenService.KIND_SIGN, "ASSISTANT"));
        assertEquals(422, wrongRole.getCode());
        BizException wrongKind = assertThrows(BizException.class,
                () -> service.verifyOnly(principal, "wo_1", null, "shot", "PRINCIPAL"));
        assertEquals(422, wrongKind.getCode());
    }

    @Test
    void shotTokenStillRejectsTampering() {
        String token = (String) service.issue("wo_1", "ci_A-1-06", 29.71921, 106.63352,
                0L, 200, EvidenceTokenService.TTL_SHOT_SECONDS).get("token");
        int dot = token.indexOf('.');
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"oid\":\"wo_1\",\"iid\":\"ci_A-1-06\",\"lat\":0,\"lng\":0,\"st\":1,\"exp\":9999999999,\"n\":\"x\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = forgedBody + token.substring(dot);
        BizException e = assertThrows(BizException.class,
                () -> service.verifyOnly(forged, "wo_1", "ci_A-1-06"));
        assertEquals(422, e.getCode());
    }
}