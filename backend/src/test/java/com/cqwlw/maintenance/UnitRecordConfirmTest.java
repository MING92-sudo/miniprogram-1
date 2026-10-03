package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.UnitRecordService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用单位确认（本机确认端点 POST /unit/records/{id}/confirm）：
 * 已确认记录不可被二次提交覆盖签字/满意度——与 confirmByToken 行为一致
 * （docs/05 §2.9 冻结与不可变、§10.4 #13「确认后状态不可重复提交」）。
 */
class UnitRecordConfirmTest {

    private static final String RECORD_ID = "ur_1";

    private MaintainRecordMapper recordMapper;
    private UnitRecordService service;
    private MaintainRecord record;

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        service = new UnitRecordService(recordMapper, mock(WorkOrderService.class));
        record = new MaintainRecord();
        record.id = RECORD_ID;
        record.confirmStatus = "PENDING";
        record.satisfaction = 0;
        record.signatureUrl = "";
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
    }

    @Test
    void firstConfirmWritesSignature() {
        Map<String, Object> r = service.confirm(RECORD_ID, Map.of("signatureUrl", "https://x/sign1.png", "satisfaction", 5));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("CONFIRMED", record.confirmStatus);
        assertEquals("https://x/sign1.png", record.signatureUrl);
        assertEquals(5, record.satisfaction.intValue());
        verify(recordMapper, times(1)).updateById(any(MaintainRecord.class));
    }

    @Test
    void secondConfirmIsIdempotentAndKeepsOriginalSignature() {
        service.confirm(RECORD_ID, Map.of("signatureUrl", "https://x/sign1.png", "satisfaction", 5));

        Map<String, Object> again = service.confirm(RECORD_ID, Map.of("signatureUrl", "https://x/forged.png", "satisfaction", 1));

        assertEquals(Boolean.TRUE, again.get("ok"));
        assertEquals(Boolean.TRUE, again.get("already"), "重复确认须回 already=true，供前端提示已归档");
        assertEquals("https://x/sign1.png", record.signatureUrl, "原签字不得被覆盖");
        assertEquals(5, record.satisfaction.intValue(), "原满意度不得被覆盖");
        verify(recordMapper, times(1)).updateById(any(MaintainRecord.class));
    }

    @Test
    void confirmUnknownRecordIs404() {
        when(recordMapper.selectById("ur_missing")).thenReturn(null);
        try {
            service.confirm("ur_missing", Map.of());
            assertTrue(false, "不存在的记录应抛 1404");
        } catch (com.cqwlw.maintenance.common.BizException e) {
            assertEquals(1404, e.getCode());
        }
        verify(recordMapper, never()).updateById(any(MaintainRecord.class));
    }
}
