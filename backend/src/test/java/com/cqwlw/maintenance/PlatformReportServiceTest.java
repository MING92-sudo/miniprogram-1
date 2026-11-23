package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.RegUploadLog;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 失败不自动重试（AGENTS §2.3）； */
class PlatformReportServiceTest {

    private MaintainRecordMapper recordMapper;
    private RegUploadLogMapper logMapper;
    private WorkOrderMapper orderMapper;
    private PlatformClient platformClient;
    private PlatformTokenService tokenService;
    private PlatformProperties props;
    private PlatformReportService service;

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        logMapper = mock(RegUploadLogMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        platformClient = mock(PlatformClient.class);
        tokenService = mock(PlatformTokenService.class);
        props = new PlatformProperties();
        service = new PlatformReportService(recordMapper, logMapper,
                platformClient, tokenService, props, orderMapper);
        when(recordMapper.beginUpload(anyString())).thenReturn(1);
        when(recordMapper.beginLegacyUpload(anyString())).thenReturn(1);
    }

    private MaintainRecord record(String reportStatus) {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.originalRecordId = "1948179075784507801";
        r.reportStatus = reportStatus;
        r.retryCount = 0;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("elevatorCode", "212520");
        payload.put("unitPrincipal", "单位负责人");
        payload.put("unitPrincipalPhone", "13800000001");
        payload.put("elevatorAdminister", "安全管理员");
        payload.put("elevatorAdministerPhone", "13800000002");
        payload.put("emergencyPhone", "023-12345678");
        payload.put("insideNumber", "KT-01");
        payload.put("workMenegerName", "维保经理");
        payload.put("workMenegerPhone", "13800000003");
        payload.put("workMan1Id", "platform-worker-1");
        payload.put("workMan2Id", "platform-worker-2");
        payload.put("startTime", "2026-11-22 08:00:00");
        payload.put("endTime", "2026-11-22 09:00:00");
        payload.put("workType", "HM");
        payload.put("recorder", "主维保");
        payload.put("recorderPhone", "13800000004");
        payload.put("originalRecordId", "1948179075784507801");
        payload.put("problemCode", List.of("S0"));
        payload.put("deviceCode", "31305001122014017242");
        payload.put("nextMaintenanceDate", "2026-12-07");
        r.reportPayloadJson = com.cqwlw.maintenance.util.JsonUtil.write(payload);
        return r;
    }

    @Test
    void attemptUploadUsesFrozenSnapshotAndLogsSuccess() {
        when(tokenService.configured()).thenReturn(true);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        MaintainRecord r = record("SUBMITTED");

        assertEquals("REPORTED", service.attemptUpload(r));
        assertEquals("REPORTED", r.reportStatus);
        assertEquals(1, r.retryCount);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(platformClient).uploadMaintenanceRecord(captor.capture());
        assertEquals("212520", captor.getValue().get("elevatorCode"));
        verify(logMapper).insert(any(RegUploadLog.class));
    }

    @Test
    void attemptUploadFailureMarksFailedWithoutAutoRetry() {
        when(tokenService.configured()).thenReturn(true);
        when(platformClient.uploadMaintenanceRecord(any()))
                .thenThrow(new BizException(2002, "平台返回错误: 电梯编码必填"));
        MaintainRecord r = record("SUBMITTED");

        assertEquals("FAILED", service.attemptUpload(r));
        assertEquals("FAILED", r.reportStatus);
        // AGENTS §2.3：失败不自动重试，只调用一次
        verify(platformClient, times(1)).uploadMaintenanceRecord(any());
        ArgumentCaptor<RegUploadLog> captor = ArgumentCaptor.forClass(RegUploadLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().status);
        assertEquals("UPLOAD", captor.getValue().action);
    }

    @Test
    void attemptUploadReturnsUnknownWhenFallbackWriteSucceeds() {
        when(tokenService.configured()).thenReturn(true);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        when(recordMapper.updateReportProgress(anyString(), eq("REPORTED"), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));
        MaintainRecord r = record("SUBMITTED");

        assertEquals("UNKNOWN", service.attemptUpload(r));

        assertEquals("UNKNOWN", r.reportStatus);
        verify(recordMapper).updateReportProgress("ur_1", "UNKNOWN", 1);
        verify(recordMapper, never()).updateReportProgress(anyString(), eq("FAILED"), anyInt());
        ArgumentCaptor<RegUploadLog> captor = ArgumentCaptor.forClass(RegUploadLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("UNKNOWN", captor.getValue().status);
        assertEquals("UPLOAD", captor.getValue().action);
    }

    @Test
    void localAuditFailureAfterSuccessIsOutcomeUnknown() {
        when(tokenService.configured()).thenReturn(true);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        when(logMapper.insert(any(RegUploadLog.class))).thenThrow(new RuntimeException("log unavailable"));
        MaintainRecord r = record("SUBMITTED");

        assertEquals("UNKNOWN", service.attemptUpload(r));

        assertEquals("UNKNOWN", r.reportStatus);
        verify(recordMapper).updateReportProgress("ur_1", "UNKNOWN", 1);
        verify(recordMapper, never()).updateReportProgress(anyString(), eq("FAILED"), anyInt());
    }

    @Test
    void attemptUploadStaysReportingWhenBothLocalWritesFail() {
        when(tokenService.configured()).thenReturn(true);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        when(recordMapper.updateReportProgress(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));
        MaintainRecord r = record("SUBMITTED");

        assertEquals("REPORTING", service.attemptUpload(r));

        assertEquals("REPORTING", r.reportStatus);
        verify(recordMapper, never()).updateReportProgress(anyString(), eq("FAILED"), anyInt());
    }

    @Test
    void reuploadLocalFailureAfterSuccessIsOutcomeUnknown() {
        MaintainRecord r = record("FAILED");
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.beginReupload("ur_1")).thenReturn(1);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        when(recordMapper.updateReportProgress(anyString(), eq("REPORTED"), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));

        MaintainRecord out = service.reupload("ur_1");

        assertEquals("UNKNOWN", out.reportStatus);
        verify(recordMapper).updateReportProgress("ur_1", "UNKNOWN", 1);
        verify(recordMapper, never()).completeReupload(anyString(), anyString());
    }

    @Test
    void legacyLocalFailureAfterSuccessIsOutcomeUnknown() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord r = record("SUBMITTED");
        when(recordMapper.selectList(any())).thenReturn(List.of(r));
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenReturn(Map.of("code", 200));
        when(recordMapper.updateReportProgress(anyString(), eq("REPORTED"), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));

        PlatformReportService.SyncLegacyResult result = service.syncLegacy();

        assertEquals(0, result.uploaded());
        assertEquals(1, result.stuck());
        assertEquals("UNKNOWN", r.reportStatus);
        verify(recordMapper).updateReportProgress("ur_1", "UNKNOWN", 1);
        verify(recordMapper, never()).completeLegacyUpload(anyString(), anyString());
    }

    @Test
    void unknownOutcomeCannotBeReuploaded() {
        MaintainRecord unknown = record("UNKNOWN");
        when(recordMapper.selectById("ur_1")).thenReturn(unknown);

        BizException e = assertThrows(BizException.class, () -> service.reupload("ur_1"));

        assertEquals(1003, e.getCode());
        verify(recordMapper, never()).beginReupload(anyString());
        verify(platformClient, never()).uploadMaintenanceRecord(any());
    }

    @Test
    void attemptUploadSkippedWhenPlatformNotConfigured() {
        when(tokenService.configured()).thenReturn(false);
        assertEquals("SUBMITTED", service.attemptUpload(record("SUBMITTED")));
        verify(platformClient, never()).uploadMaintenanceRecord(any());
    }

    @Test
    void reuploadOnlyAllowedForFailedRecords() {
        MaintainRecord reported = record("REPORTED");
        when(recordMapper.selectById("ur_1")).thenReturn(reported);
        BizException e = assertThrows(BizException.class,
                () -> service.reupload("ur_1"));
        assertEquals(1003, e.getCode());
        verify(platformClient, never()).uploadMaintenanceRecord(any());
    }

    @Test
    void reuploadFailedRecordSucceedsAndLogsReupload() {
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));
        when(recordMapper.beginReupload("ur_1")).thenReturn(1);
        MaintainRecord r = record("FAILED");
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        MaintainRecord out = service.reupload("ur_1");
        assertEquals("REPORTED", out.reportStatus);
        ArgumentCaptor<RegUploadLog> captor = ArgumentCaptor.forClass(RegUploadLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("REUPLOAD", captor.getValue().action);
    }

    @Test
    void reuploadRejectsIncompleteFrozenSnapshot() {
        MaintainRecord r = record("FAILED");
        r.reportPayloadJson = com.cqwlw.maintenance.util.JsonUtil.write(Map.of("elevatorCode", "212520"));
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.beginReupload("ur_1")).thenReturn(1);

        BizException e = assertThrows(BizException.class, () -> service.reupload("ur_1"));

        assertEquals(422, e.getCode());
        verify(platformClient, never()).uploadMaintenanceRecord(any());
    }

    @Test
    void legacySyncDoesNothingWhenSwitchOff() {
        props.setLegacyUploadEnabled(false);
        when(tokenService.configured()).thenReturn(true);
        PlatformReportService.SyncLegacyResult result = service.syncLegacy();
        assertEquals(0, result.uploaded());
        assertEquals(0, result.stuck());
        verify(platformClient, never()).uploadMaintenanceRecord(any());
    }

    @Test
    void legacySyncUploadsOnlySubmittedLeafRecordsWhenSwitchOn() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord done = record("REPORTED");
        MaintainRecord failed = record("FAILED");
        MaintainRecord submitted = record("SUBMITTED");
        when(recordMapper.selectList(any())).thenReturn(List.of(done, failed, submitted));
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenReturn(Map.of("code", 200));

        assertEquals(1, service.syncLegacy().uploaded());
        verify(platformClient, times(1)).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void concurrentReuploadAllowsOnlyOnePlatformCall() {
        MaintainRecord first = record("FAILED");
        MaintainRecord second = record("FAILED");
        when(recordMapper.selectById("ur_1")).thenReturn(first, second);
        when(recordMapper.beginReupload("ur_1")).thenReturn(1, 0);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));

        service.reupload("ur_1");
        BizException loser = assertThrows(BizException.class, () -> service.reupload("ur_1"));

        assertEquals(1003, loser.getCode());
        verify(platformClient, times(1)).uploadMaintenanceRecord(any());
    }

    @Test
    void reuploadRejectsSupersededRecord() {
        MaintainRecord old = record("FAILED");
        when(recordMapper.selectById("ur_1")).thenReturn(old);
        when(recordMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.reupload("ur_1"));

        assertEquals(1003, e.getCode());
        verify(platformClient, never()).uploadMaintenanceRecord(any());
        verify(recordMapper, never()).beginReupload(anyString());
    }

    @Test
    void legacySyncSkipsSupersededSubmittedRecord() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord parent = record("SUBMITTED");
        MaintainRecord child = record("DRAFT");
        child.id = "ur_2";
        child.prevVersionId = parent.id;
        when(recordMapper.selectList(any())).thenReturn(List.of(parent), List.of(child));
        when(recordMapper.selectCount(any())).thenReturn(1L);

        PlatformReportService.SyncLegacyResult skipped = service.syncLegacy();
        assertEquals(0, skipped.uploaded());
        assertEquals(0, skipped.stuck());
        verify(platformClient, never()).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void legacySyncChecksSuccessorsInBatchWithoutPerRecordCount() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord first = record("SUBMITTED");
        MaintainRecord second = record("SUBMITTED");
        second.id = "ur_2";
        MaintainRecord third = record("SUBMITTED");
        third.id = "ur_3";
        when(recordMapper.selectList(any())).thenReturn(List.of(first, second, third), List.of());
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenReturn(Map.of("code", 200));

        assertEquals(3, service.syncLegacy().uploaded());
        verify(recordMapper, never()).selectCount(any());
        verify(recordMapper, times(2)).selectList(any());
    }

    @Test
    void reuploadFailureReturnsRecordToFailed() {
        MaintainRecord r = record("FAILED");
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.beginReupload("ur_1")).thenReturn(1);
        when(platformClient.uploadMaintenanceRecord(any())).thenThrow(new RuntimeException("network"));

        assertThrows(RuntimeException.class, () -> service.reupload("ur_1"));

        assertEquals("FAILED", r.reportStatus);
        verify(recordMapper).completeReupload("ur_1", "FAILED");
    }

    @Test
    void concurrentLegacySyncClaimsRecordOnlyOnce() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        when(recordMapper.selectList(any())).thenReturn(List.of(record("SUBMITTED"), record("SUBMITTED")));
        when(recordMapper.beginLegacyUpload("ur_1")).thenReturn(1, 0);
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenReturn(Map.of("code", 200));

        assertEquals(1, service.syncLegacy().uploaded());
        verify(platformClient, times(1)).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void legacyUploadFailureRestoresSubmittedWithoutRetry() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord r = record("SUBMITTED");
        when(recordMapper.selectList(any())).thenReturn(List.of(r));
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenThrow(new RuntimeException("network"));

        assertEquals(0, service.syncLegacy().uploaded());
        assertEquals("SUBMITTED", r.reportStatus);
        verify(recordMapper).completeLegacyUpload("ur_1", "SUBMITTED");
        verify(platformClient, times(1)).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void attemptUploadClaimsReportingBeforePlatformCall() {
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord r = record("SUBMITTED");
        AtomicReference<String> statusAtPlatformCall = new AtomicReference<>();
        when(platformClient.uploadMaintenanceRecord(any())).thenAnswer(inv -> {
            statusAtPlatformCall.set(r.reportStatus);
            return Map.of("code", 200);
        });

        assertEquals("REPORTED", service.attemptUpload(r));

        assertEquals("REPORTING", statusAtPlatformCall.get());
        InOrder inOrder = inOrder(recordMapper, platformClient);
        inOrder.verify(recordMapper).beginUpload("ur_1");
        inOrder.verify(platformClient).uploadMaintenanceRecord(any());
    }

    @Test
    void attemptUploadRejectsWhenClaimFails() {
        when(tokenService.configured()).thenReturn(true);
        when(recordMapper.beginUpload("ur_1")).thenReturn(0);
        MaintainRecord r = record("SUBMITTED");

        BizException e = assertThrows(BizException.class, () -> service.attemptUpload(r));

        assertEquals(1003, e.getCode());
        assertEquals("SUBMITTED", r.reportStatus);
        verify(platformClient, never()).uploadMaintenanceRecord(any());
        verify(recordMapper, never()).updateReportProgress(anyString(), anyString(), anyInt());
    }

    @Test
    void syncLegacySkipsStuckAndContinues() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord stuck = record("SUBMITTED");
        MaintainRecord ok = record("SUBMITTED");
        ok.id = "ur_2";
        when(recordMapper.selectList(any())).thenReturn(List.of(stuck, ok));
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenReturn(Map.of("code", 200));
        when(recordMapper.updateReportProgress(eq("ur_1"), anyString(), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));

        PlatformReportService.SyncLegacyResult result = service.syncLegacy();

        assertEquals(1, result.uploaded());
        assertEquals(1, result.stuck());
        assertEquals("LEGACY_UPLOADING", stuck.reportStatus);
        assertEquals("REPORTED", ok.reportStatus);
        verify(platformClient, times(2)).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void reuploadSyncsWorkOrderStatus() {
        MaintainRecord r = record("FAILED");
        r.workOrderId = "wo_1";
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.beginReupload("ur_1")).thenReturn(1);
        when(platformClient.uploadMaintenanceRecord(any())).thenReturn(Map.of("code", 200));

        MaintainRecord out = service.reupload("ur_1");

        assertEquals("REPORTED", out.reportStatus);
        verify(orderMapper).updateReportStatus("wo_1", "REPORTED");

        MaintainRecord stuck = record("FAILED");
        stuck.id = "ur_2";
        stuck.workOrderId = "wo_2";
        when(recordMapper.selectById("ur_2")).thenReturn(stuck);
        when(recordMapper.beginReupload("ur_2")).thenReturn(1);
        when(recordMapper.updateReportProgress(eq("ur_2"), anyString(), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));

        MaintainRecord stuckOut = service.reupload("ur_2");

        assertEquals("REUPLOADING", stuckOut.reportStatus);
        verify(orderMapper).updateReportStatus("wo_2", "REUPLOADING");
    }

    @Test
    void digestMasksPhoneNumbersAndKeepsCodes() {
        Map<String, Object> payload = Map.of(
                "recorderPhone", "13800000001",
                "elevatorCode", "212520",
                "problemCode", List.of("S0"));
        String digest = PlatformReportService.digest(payload);
        assertTrue(digest.contains("212520"));
        assertTrue(digest.contains("S0"));
        assertTrue(!digest.contains("13800000001"));
        assertTrue(digest.contains("138****0001"));
    }

    @Test
    void reconcileRejectsInvalidOutcome() {
        BizException e = assertThrows(BizException.class,
                () -> service.reconcile("ur_1", "FOO", "note"));

        assertEquals(422, e.getCode());
        verifyNoInteractions(recordMapper, logMapper, orderMapper, platformClient);
    }

    @Test
    void reconcileRejectsNonStuckStatus() {
        MaintainRecord r = record("REPORTED");
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        BizException e = assertThrows(BizException.class,
                () -> service.reconcile("ur_1", "REPORTED", "note"));

        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("当前状态无需核对"));
        verify(recordMapper, never()).reconcileReport(anyString(), anyString());
        verifyNoInteractions(logMapper, platformClient);
    }

    @Test
    void reconcileRejectsSupersededRecord() {
        MaintainRecord r = record("REPORTING");
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service.reconcile("ur_1", "REPORTED", "note"));

        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("记录已有后继版本"));
        verify(recordMapper, never()).reconcileReport(anyString(), anyString());
        verifyNoInteractions(logMapper, platformClient);
    }

    @Test
    void reconcileRejectsConcurrentChange() {
        MaintainRecord r = record("REPORTING");
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.reconcileReport("ur_1", "REPORTED")).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> service.reconcile("ur_1", "REPORTED", "note"));

        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("状态已变化，请刷新后查看"));
        verifyNoInteractions(logMapper, orderMapper, platformClient);
    }

    @Test
    void reconcileMarksReportedWithAuditAndOrderSync() {
        MaintainRecord r = record("REPORTING");
        r.workOrderId = "wo_1";
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.reconcileReport("ur_1", "REPORTED")).thenReturn(1);

        MaintainRecord out = service.reconcile("ur_1", "REPORTED", "平台已核实，联系人 13800001111");

        assertEquals("REPORTED", out.reportStatus);
        ArgumentCaptor<RegUploadLog> captor = ArgumentCaptor.forClass(RegUploadLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("RECONCILE", captor.getValue().action);
        assertEquals("SUCCESS", captor.getValue().status);
        assertTrue(captor.getValue().platformMessage.contains("平台已核实"));
        assertTrue(!captor.getValue().platformMessage.contains("13800001111"));
        assertTrue(captor.getValue().platformMessage.contains("138****1111"));
        verify(orderMapper).updateReportStatus("wo_1", "REPORTED");
        verifyNoInteractions(platformClient);
    }

    @Test
    void reconcileMarksFailedAllowsManualReupload() {
        MaintainRecord r = record("UNKNOWN");
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.reconcileReport("ur_1", "FAILED")).thenReturn(1);

        MaintainRecord out = service.reconcile("ur_1", "FAILED", "平台无此记录");

        assertEquals("FAILED", out.reportStatus);
        ArgumentCaptor<RegUploadLog> captor = ArgumentCaptor.forClass(RegUploadLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("RECONCILE", captor.getValue().action);
        assertEquals("MANUAL_FAILED", captor.getValue().status);
        verifyNoInteractions(platformClient);
    }

    @Test
    void reconcileSkipsOrderSyncWhenNoWorkOrderId() {
        MaintainRecord r = record("REPORTING");
        r.workOrderId = null;
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.reconcileReport("ur_1", "REPORTED")).thenReturn(1);

        MaintainRecord out = service.reconcile("ur_1", "REPORTED", "");

        assertEquals("REPORTED", out.reportStatus);
        verify(logMapper).insert(any(RegUploadLog.class));
        verifyNoInteractions(orderMapper, platformClient);
    }
}
