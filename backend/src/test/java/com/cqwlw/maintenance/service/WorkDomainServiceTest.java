package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 归档记录修订：明确变更集、完整工作流快照、可重新流转且不自动重报。 */
class WorkDomainServiceTest {

    private final MaintainRecordMapper recordMapper = mock(MaintainRecordMapper.class);
    private final FaultMapper faultMapper = mock(FaultMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RecordLifecycleService lifecycle = mock(RecordLifecycleService.class);
    private final WorkDomainService service = new WorkDomainService(recordMapper, faultMapper, audit, lifecycle);

    private MaintainRecord archived() {
        MaintainRecord r = new MaintainRecord();
        r.id = "r1";
        r.confirmStatus = "CONFIRMED";
        r.lifecycleStatus = "ARCHIVED";
        r.version = 1;
        r.itemsJson = "[{\"name\":\"制动器\",\"result\":\"NORMAL\"}]";
        r.previewContextJson = "{\"address\":\"重庆市渝北区\"}";
        r.originalRecordId = "OR-1";
        r.workOrderId = "wo-1";
        r.nextMaintenanceDate = LocalDate.of(2026, 12, 1);
        return r;
    }

    @org.junit.jupiter.api.BeforeEach
    void stubLifecycle() {
        when(lifecycle.initialStatus()).thenReturn("PENDING_REVIEW");
    }

    @Test
    void amendOnlyArchived() {
        MaintainRecord r = archived();
        r.confirmStatus = "PENDING";
        when(recordMapper.selectById("r1")).thenReturn(r);
        BizException e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "reason", "items", List.of()), "mgr"));
        assertEquals(422, e.getCode());

        r.confirmStatus = "CONFIRMED";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "reason", "items", List.of()), "mgr"));
        assertEquals(422, e.getCode());
    }

    @Test
    void amendCreatesV2WithOriginalPreviewAndWorkflowState() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        MaintainRecord v2 = service.amend("r1", Map.of(
                "reason", "更正项",
                "items", List.of(Map.of("name", "制动器", "result", "ABNORMAL", "abnormalDesc", "间隙异常"))), "mgr");
        assertEquals(2, v2.version);
        assertEquals("r1", v2.prevVersionId);
        assertEquals("{\"address\":\"重庆市渝北区\"}", v2.previewContextJson);
        assertTrue(v2.itemsJson.contains("ABNORMAL"));
        assertEquals("PENDING_REVIEW", v2.lifecycleStatus);
        assertEquals("PENDING", v2.confirmStatus);
        assertEquals("DRAFT", v2.reportStatus);
        assertNull(v2.reportPayloadJson);
        assertNull(v2.signatureUrl);
        assertNull(v2.archiveFileId);
        assertNull(v2.archiveHash);
        verify(recordMapper).insert(any(MaintainRecord.class));
    }

    @Test
    void amendCopiesRequiredSnapshotsAndAppliesTodoAndSafety() {
        MaintainRecord v1 = archived();
        v1.useUnitId = "uu1";
        v1.useUnitName = "启元物业";
        v1.elevatorId = "e1";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        MaintainRecord v2 = service.amend("r1", Map.of(
                "reason", "内容有误",
                "todoDesc", "复测制动间隙",
                "safety", Map.of("warning", true, "twoPerson", true)), "emp1");
        assertEquals("uu1", v2.useUnitId);
        assertEquals("启元物业", v2.useUnitName);
        assertEquals("e1", v2.elevatorId);
        assertEquals("OR-1", v2.originalRecordId);
        assertEquals("wo-1", v2.workOrderId);
        assertEquals(v1.nextMaintenanceDate, v2.nextMaintenanceDate);
        assertEquals("复测制动间隙", v2.todoDesc);
        assertTrue(v2.safetyJson.contains("twoPerson"));
        assertEquals(v1.itemsJson, v2.itemsJson);
    }

    @Test
    void amendRequiresReasonAndExplicitChangeSet() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "", "items", List.of()), "mgr"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "仅理由"), "mgr"));
    }

    @Test
    void amendCopiesNullSnapshotWithoutElevatorLookup() {
        MaintainRecord v1 = archived();
        v1.useUnitId = null;
        v1.useUnitName = null;
        v1.elevatorId = null;
        when(recordMapper.selectById("r1")).thenReturn(v1);
        MaintainRecord v2 = service.amend("r1", Map.of("reason", "内容有误", "todoDesc", "待办"), "emp1");
        assertNull(v2.useUnitId);
        assertNull(v2.useUnitName);
        assertNull(v2.elevatorId);
        assertNotNull(v2.shareToken);
    }

    @Test
    void proxyValidatesRecordingNo() {
        Fault f = new Fault();
        f.id = "f1";
        when(faultMapper.selectById("f1")).thenReturn(f);
        assertThrows(BizException.class, () -> service.proxyRepair("f1", Map.of("proxySource", "PHONE", "recordingNo", "BAD"), "u"));
        Fault ok = service.proxyRepair("f1", Map.of("proxySource", "PHONE", "recordingNo", "REC-1234"), "u");
        assertEquals("PHONE", ok.proxySource);
        assertEquals("u", ok.proxyBy);
    }

}
