package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.RegUploadLog;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 平台上报服务（docs/07-full-test 实测口径沉淀）：
 * 2.6 报文直接取签退冻结快照；失败不自动重试（AGENTS §2.3）；
 * reupload 仅对 FAILED 放行；2.8 开关关闭时不推送；日志摘要脱敏。
 */
class PlatformReportServiceTest {

    private MaintainRecordMapper recordMapper;
    private RegUploadLogMapper logMapper;
    private PlatformClient platformClient;
    private PlatformTokenService tokenService;
    private PlatformProperties props;
    private PlatformReportService service;

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        logMapper = mock(RegUploadLogMapper.class);
        platformClient = mock(PlatformClient.class);
        tokenService = mock(PlatformTokenService.class);
        props = new PlatformProperties();
        service = new PlatformReportService(recordMapper, logMapper,
                platformClient, tokenService, props);
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
        payload.put("recorderPhone", "13800000001");
        payload.put("problemCode", List.of("S0"));
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
    void legacySyncDoesNothingWhenSwitchOff() {
        props.setLegacyUploadEnabled(false);
        when(tokenService.configured()).thenReturn(true);
        assertEquals(0, service.syncLegacy());
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

        assertEquals(1, service.syncLegacy());
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

        assertEquals(0, service.syncLegacy());
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

        assertEquals(3, service.syncLegacy());
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

        assertEquals(1, service.syncLegacy());
        verify(platformClient, times(1)).uploadLegacyRecord(any(), any(), any());
    }

    @Test
    void legacyUploadFailureRestoresSubmittedWithoutRetry() {
        props.setLegacyUploadEnabled(true);
        when(tokenService.configured()).thenReturn(true);
        MaintainRecord r = record("SUBMITTED");
        when(recordMapper.selectList(any())).thenReturn(List.of(r));
        when(platformClient.uploadLegacyRecord(any(), any(), any())).thenThrow(new RuntimeException("network"));

        assertEquals(0, service.syncLegacy());
        assertEquals("SUBMITTED", r.reportStatus);
        verify(recordMapper).completeLegacyUpload("ur_1", "SUBMITTED");
        verify(platformClient, times(1)).uploadLegacyRecord(any(), any(), any());
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
}
