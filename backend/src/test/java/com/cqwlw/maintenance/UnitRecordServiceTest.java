package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.UnitRecordService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 签字与归档解耦：归档失败不拖垮已落库的签字，响应显式带 archived。 */
class UnitRecordServiceTest {

    private MaintainRecordMapper recordMapper;
    private RecordLifecycleService lifecycle;
    private UnitRecordService service;

    /** LambdaUpdateWrapper 列名解析需 TableInfo 缓存（纯 Mockito 单测无 MyBatis 运行时） */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
    }

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        lifecycle = mock(RecordLifecycleService.class);
        service = new UnitRecordService(recordMapper, mock(WorkOrderService.class),
                mock(FileStorageService.class), mock(ElevatorMapper.class));
        service.setRecordLifecycleService(lifecycle);
        when(recordMapper.selectCount(any())).thenReturn(0L); // 全部最新版本
        when(recordMapper.update(any(), any())).thenReturn(1); // 条件更新命中（先签为准）
    }

    private MaintainRecord signable() {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.shareToken = "sg-1";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.confirmStatus = "PENDING";
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        return r;
    }

    @Test
    void signSucceedsWhenArchiveFails() {
        MaintainRecord r = signable();
        doThrow(new RuntimeException("PDF 渲染失败")).when(lifecycle).onClientSigned(r);

        Map<String, Object> out = service.confirmByToken("ur_1", "sg-1",
                Map.of("signatureFileId", "file_client"));

        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(Boolean.FALSE, out.get("archived"));
        assertEquals("CONFIRMED", r.confirmStatus); // 签字不回滚
        assertEquals("SIGNED", r.lifecycleStatus);  // 停留待归档
    }

    @Test
    void signReportsArchivedTrueWhenArchiveSucceeds() {
        MaintainRecord r = signable();

        Map<String, Object> out = service.confirmByToken("ur_1", "sg-1",
                Map.of("signatureFileId", "file_client"));

        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(Boolean.TRUE, out.get("archived"));
        assertEquals("CONFIRMED", r.confirmStatus);
        assertEquals("SIGNED", r.lifecycleStatus);
    }

    @Test
    void uploadStatusMapsInflightStatesToUnknown() {
        assertEquals("UNKNOWN", UnitRecordService.uploadStatus("REPORTING"));
        assertEquals("UNKNOWN", UnitRecordService.uploadStatus("REUPLOADING"));
        assertEquals("UNKNOWN", UnitRecordService.uploadStatus("LEGACY_UPLOADING"));
        assertEquals("UNKNOWN", UnitRecordService.uploadStatus("UNKNOWN"));
        assertEquals("SUCCESS", UnitRecordService.uploadStatus("REPORTED"));
        assertEquals("FAILED", UnitRecordService.uploadStatus("FAILED"));
        assertEquals("PENDING", UnitRecordService.uploadStatus("PENDING"));
    }
}
