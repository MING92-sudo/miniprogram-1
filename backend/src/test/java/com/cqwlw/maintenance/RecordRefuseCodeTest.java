package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.RecordPdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用单位拒签分类码（V56，docs/04 V2.80）：refuseCode ∈ R1—R5 必填（旧调用兼容回退「原因必填」）；
 * R5 必须附说明；refuseReason 落库为「分类标签：说明」组合文本；refuse_code 单列持久化。
 */
class RecordRefuseCodeTest {

    private static final String RECORD_ID = "ur_1";
    private static final String TOKEN = "sg_tok";

    private MaintainRecordMapper recordMapper;
    private RecordLifecycleService service;
    private MaintainRecord record;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
        recordMapper = mock(MaintainRecordMapper.class);
        service = new RecordLifecycleService(recordMapper, mock(WorkOrderMapper.class),
                mock(EmployeeMapper.class), mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                mock(RecordPdfService.class), mock(FileStorageService.class), mock(SysConfigMapper.class));
        record = new MaintainRecord();
        record.id = RECORD_ID;
        record.shareToken = TOKEN;
        record.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        record.confirmStatus = "PENDING";
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(recordMapper.selectCount(any())).thenReturn(0L); // 无后续版本
        when(recordMapper.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void refuseWithCodePersistsCodeAndComposedReason() {
        Map<String, Object> out = service.refuseByToken(RECORD_ID, TOKEN, "R3", "机房照片看不清");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("REFUSED_RETURNED", record.lifecycleStatus);
        assertEquals("R3", record.refuseCode);
        assertEquals("现场照片不清晰或与实际不符：机房照片看不清", record.refuseReason);
    }

    @Test
    void refuseWithCodeOnlyUsesLabel() {
        service.refuseByToken(RECORD_ID, TOKEN, "R2", null);
        assertEquals("R2", record.refuseCode);
        assertEquals("维保项目漏项或未按单执行", record.refuseReason);
    }

    @Test
    void invalidCodeRejectedWithoutSideEffect() {
        BizException e = assertThrows(BizException.class,
                () -> service.refuseByToken(RECORD_ID, TOKEN, "R9", "x"));
        assertEquals(422, e.getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", record.lifecycleStatus);
    }

    @Test
    void r5RequiresDetail() {
        BizException e = assertThrows(BizException.class,
                () -> service.refuseByToken(RECORD_ID, TOKEN, "R5", " "));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("必须填写拒签说明"), e.getMessage());
        service.refuseByToken(RECORD_ID, TOKEN, "R5", "物业内部流程原因");
        assertEquals("R5", record.refuseCode);
        assertEquals("其他：物业内部流程原因", record.refuseReason);
    }

    /** 兼容期：旧客户端未传分类码 → 回退原「原因必填」，不写 refuse_code */
    @Test
    void legacyWithoutCodeFallsBackToReasonRequired() {
        assertThrows(BizException.class, () -> service.refuseByToken(RECORD_ID, TOKEN, ""));
        Map<String, Object> out = service.refuseByToken(RECORD_ID, TOKEN, " 记录与现场不一致 ");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertNull(record.refuseCode);
        assertEquals("记录与现场不一致", record.refuseReason);
    }
}
