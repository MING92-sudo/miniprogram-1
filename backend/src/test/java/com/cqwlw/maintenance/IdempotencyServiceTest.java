package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4：无响应的悬挂幂等记录（业务异常后未 commit）10 分钟过期放行——
 * 否则离线队列同 key 重试永远 422，任务永久卡死。
 */
class IdempotencyServiceTest {

    private final IdempotencyKeyMapper mapper = mock(IdempotencyKeyMapper.class);
    private final IdempotencyService service = new IdempotencyService(mapper);

    private IdempotencyKey row(String responseJson, java.time.LocalDateTime createdAt) {
        IdempotencyKey k = new IdempotencyKey();
        k.idemKey = "k1";
        k.responseJson = responseJson;
        k.createdAt = createdAt;
        return k;
    }

    @Test
    void staleEmptyRecordIsReleasedAfter10Minutes() {
        when(mapper.selectById("k1"))
                .thenReturn(row(null, TimeUtil.now().minusMinutes(11)));
        assertDoesNotThrow(() -> service.begin("k1", "/work-orders/x"));
        verify(mapper).deleteById("k1");
    }

    @Test
    void freshEmptyRecordStillRejected() {
        when(mapper.selectById("k1")).thenReturn(row(null, TimeUtil.now()));
        assertThrows(BizException.class, () -> service.begin("k1", "/work-orders/x"));
    }

    @Test
    void completedRecordReplays() {
        when(mapper.selectById("k1")).thenReturn(row("{\"code\":0}", TimeUtil.now()));
        assertTrue(service.begin("k1", "/work-orders/x").replayed());
    }
}
