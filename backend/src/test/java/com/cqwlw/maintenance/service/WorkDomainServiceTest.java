package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.TxTestSupport;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private final WorkDomainService service = new WorkDomainService(recordMapper, faultMapper, audit, lifecycle,
            mock(DirectoryService.class), TxTestSupport.noopTx());

    private MaintainRecord archived() {
        MaintainRecord r = new MaintainRecord();
        r.id = "r1";
        r.confirmStatus = "CONFIRMED";
        r.lifecycleStatus = "ARCHIVED";
        r.version = 1;
        r.itemsJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"制动器\",\"result\":\"NORMAL\"}]";
        r.previewContextJson = "{\"address\":\"重庆市渝北区\"}";
        r.originalRecordId = "OR-1";
        r.workOrderId = "wo-1";
        r.nextMaintenanceDate = LocalDate.of(2026, 12, 1);
        return r;
    }

    @org.junit.jupiter.api.BeforeEach
    void stubLifecycle() {
        when(lifecycle.initialStatus()).thenReturn("PENDING_REVIEW");
        when(recordMapper.selectByIdForUpdate("r1")).thenAnswer(inv -> recordMapper.selectById("r1"));
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
        MaintainRecord original = archived();
        original.itemsJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"制动器\",\"result\":\"NORMAL\",\"photoFileIds\":[\"file_1\"]}]";
        when(recordMapper.selectById("r1")).thenReturn(original);
        MaintainRecord v2 = service.amend("r1", Map.of(
                "reason", "更正项",
                "items", List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1", "name", "制动器",
                        "result", "ABNORMAL", "abnormalDesc", "间隙异常", "problemCode", "S1", "photoFileIds", List.of("file_1")))), "mgr");
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
    void amendRejectsEmptyItems() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        BizException e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "清空", "items", List.of()), "mgr"));
        assertEquals(422, e.getCode());
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.never()).insert(any(MaintainRecord.class));
    }

    @Test
    void amendRejectsMissingOriginalItem() {
        MaintainRecord v1 = archived();
        v1.itemsJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"一项\",\"result\":\"NORMAL\"},"
                + "{\"id\":\"ci_A-1-2\",\"itemCode\":\"A-1-2\",\"name\":\"二项\",\"result\":\"NORMAL\"}]";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        List<Map<String, Object>> onlyFirst = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1", "result", "NORMAL"));
        BizException e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "缺项", "items", onlyFirst), "mgr"));
        assertEquals(422, e.getCode());
    }

    @Test
    void amendPreservesUnexecutedItemsAndStableSnapshotFields() {
        MaintainRecord v1 = archived();
        v1.itemsJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"执行项\",\"result\":\"NORMAL\","
                + "\"requirement\":\"基本要求\",\"freq\":\"半月\",\"photoFileIds\":[\"file_1\"],\"photos\":[\"https://stable/1\"]},"
                + "{\"id\":\"ci_A-2-1\",\"itemCode\":\"A-2-1\",\"name\":\"未执行项\",\"notInThisRun\":true,\"result\":\"\"}]";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        List<Map<String, Object>> patch = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1",
                "result", "NORMAL", "value", "1.2", "photos", List.of("https://signed/1?s=secret")));
        MaintainRecord v2 = service.amend("r1", Map.of("reason", "保真", "items", patch), "mgr");
        assertTrue(v2.itemsJson.contains("\"notInThisRun\":true"), v2.itemsJson);
        assertTrue(v2.itemsJson.contains("\"photoFileIds\":[\"file_1\"]"), v2.itemsJson);
        assertTrue(v2.itemsJson.contains("\"requirement\":\"基本要求\""), v2.itemsJson);
        assertFalse(v2.itemsJson.contains("?s="), v2.itemsJson);
    }

    @Test
    void amendRejectsSecondVersionFromSameParent() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        when(recordMapper.selectCount(any())).thenReturn(1L);
        BizException e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "分叉", "todoDesc", "x"), "mgr"));
        assertEquals(422, e.getCode());
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.never()).insert(any(MaintainRecord.class));
    }

    @Test
    void amendRequiresAbnormalDescriptionAndProblemCode() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        List<Map<String, Object>> noDescription = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1",
                "result", "ABNORMAL", "problemCode", "S1"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "异常", "items", noDescription), "mgr"));
        List<Map<String, Object>> noCode = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1",
                "result", "ABNORMAL", "abnormalDesc", "间隙异常"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "异常", "items", noCode), "mgr"));
    }

    @Test
    void amendRebuildsProblemCodesWhenAllItemsNormal() {
        MaintainRecord v1 = archived();
        v1.problemCodesJson = "[\"S2\"]";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        List<Map<String, Object>> patch = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1", "result", "NORMAL"));
        MaintainRecord v2 = service.amend("r1", Map.of("reason", "复测正常", "items", patch), "mgr");
        assertEquals("[\"S0\"]", v2.problemCodesJson);
    }

    @Test
    void amendFromRealAdminPreviewPreservesOriginalSnapshot() {
        MaintainRecord v1 = archived();
        v1.itemsJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"执行项\",\"result\":\"NORMAL\","
                + "\"requirement\":\"基本要求\",\"photoFileIds\":[\"file_1\"]},"
                + "{\"id\":\"ci_A-2-1\",\"itemCode\":\"A-2-1\",\"name\":\"未执行项\",\"notInThisRun\":true}]";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.urlOf("file_1")).thenReturn("https://signed/1?s=secret");
        UnitRecordService previews = new UnitRecordService(recordMapper, mock(WorkOrderService.class), storage,
                mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class));
        Map<String, Object> preview = previews.adminPreview(v1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) preview.get("items");

        MaintainRecord v2 = service.amend("r1", Map.of("reason", "预览往返", "items", items), "mgr");

        assertTrue(v2.itemsJson.contains("\"notInThisRun\":true"), v2.itemsJson);
        assertTrue(v2.itemsJson.contains("\"photoFileIds\":[\"file_1\"]"), v2.itemsJson);
        assertFalse(v2.itemsJson.contains("?s="), v2.itemsJson);
    }

    @Test
    void amendRejectsRecordWhileReuploadInFlight() {
        MaintainRecord v1 = archived();
        v1.reportStatus = "REUPLOADING";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        BizException e = assertThrows(BizException.class,
                () -> service.amend("r1", Map.of("reason", "交错修订", "todoDesc", "x"), "mgr"));
        assertEquals(422, e.getCode());
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.never()).insert(any(MaintainRecord.class));
    }

    @Test
    void amendRequiresAbnormalPhotoNumericValueAndNaReason() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        List<Map<String, Object>> abnormalNoPhoto = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1",
                "result", "ABNORMAL", "abnormalDesc", "异常", "problemCode", "S1"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "无照片", "items", abnormalNoPhoto), "mgr"));
        List<Map<String, Object>> badValue = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1",
                "result", "NORMAL", "value", "abc"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "非法读数", "items", badValue), "mgr"));
        List<Map<String, Object>> noNaReason = List.of(Map.of("id", "ci_A-1-1", "itemCode", "A-1-1", "result", "NA"));
        assertThrows(BizException.class, () -> service.amend("r1", Map.of("reason", "缺原因", "items", noNaReason), "mgr"));
    }

    @Test
    void amendMatchesItemCodeOnlyPatch() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        List<Map<String, Object>> patch = List.of(Map.of("itemCode", "A-1-1", "result", "NORMAL"));
        MaintainRecord v2 = service.amend("r1", Map.of("reason", "只传业务码", "items", patch), "mgr");
        assertTrue(v2.itemsJson.contains("ci_A-1-1"));
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
