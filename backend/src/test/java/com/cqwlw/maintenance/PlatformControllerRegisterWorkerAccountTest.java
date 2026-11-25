package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.controller.PlatformController;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformSyncService;
import com.cqwlw.maintenance.service.PlatformWorkerIdService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2.4 登记成功后本地建号结果必须显式返回（employeeCreated/employeeCreateReason），不得静默吞掉失败；
 * 终止（changState=1）只解绑，不建号、不返回建号键。
 */
class PlatformControllerRegisterWorkerAccountTest {

    private PlatformClient platformClient;
    private AdminArchiveService archiveService;
    private PlatformWorkerIdService workerIdService;
    private PlatformController controller;

    @BeforeEach
    void setUp() {
        platformClient = mock(PlatformClient.class);
        archiveService = mock(AdminArchiveService.class);
        workerIdService = mock(PlatformWorkerIdService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.executeExternal(any(), any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Object> s = (Supplier<Object>) inv.getArgument(3);
            return s.get();
        });
        controller = new PlatformController(mock(PlatformSyncService.class), mock(PlatformReportService.class),
                platformClient, archiveService, workerIdService, idempotencyService);
    }

    private static MultipartFile pdf() throws Exception {
        MultipartFile f = mock(MultipartFile.class);
        when(f.isEmpty()).thenReturn(false);
        when(f.getBytes()).thenReturn(new byte[]{1, 2, 3});
        when(f.getOriginalFilename()).thenReturn("cert.pdf");
        return f;
    }

    private Map<String, Object> register() throws Exception {
        return register("0");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> register(String changState) throws Exception {
        when(platformClient.registerWorkerState(any(), any(), any())).thenReturn(Map.of());
        ApiResponse<Object> resp = controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", changState, pdf(), "key-w1", mock(HttpServletRequest.class));
        assertEquals(0, resp.getCode());
        return (Map<String, Object>) resp.getData();
    }

    @Test
    void terminateUnbindsWithoutCreatingLocalAccount() throws Exception {
        Map<String, Object> out = register("1");

        assertEquals(true, out.get("ok"));
        assertEquals("", out.get("employeeId"));
        assertEquals("13800000001", out.get("account"));
        assertFalse(out.containsKey("employeeCreated"));
        assertFalse(out.containsKey("employeeCreateReason"));
        assertFalse(out.containsKey("initialPassword"));
        assertEquals(Map.of("status", "SKIPPED"), out.get("platformBackfill"));
        verify(archiveService, never()).createEmployee(any());
        verify(archiveService, never()).employeeCertificateExists(any());
        verify(archiveService, times(1)).updateBindStatusByCertificate("CERT-1", 1);
        verify(workerIdService, never()).backfillWithRetry(any());
        verify(platformClient, times(1)).registerWorkerState(any(), any(), any());
    }

    @Test
    void phoneMutexConflictReportsRolesWithoutFailingRegistration() throws Exception {
        when(archiveService.employeeCertificateExists("CERT-1")).thenReturn(0L);
        when(archiveService.createEmployee(any())).thenThrow(new BizException(1002,
                "手机号互斥冲突，请核对角色分配（docs/01 §3.2.4）",
                Map.of("conflicts", List.of(Map.of("phone", "18223501588", "roles", List.of(
                        Map.of("role", "维保人员", "owner", ""),
                        Map.of("role", "维保经理", "owner", "渝达电梯")))))));

        Map<String, Object> out = register();

        assertEquals(true, out.get("ok"));
        assertEquals(false, out.get("employeeCreated"));
        String reason = String.valueOf(out.get("employeeCreateReason"));
        assertTrue(reason.contains("1002"), reason);
        assertTrue(reason.contains("维保经理"), reason);
        assertEquals("", out.get("employeeId"));
        assertFalse(out.containsKey("initialPassword"));
        verify(platformClient, times(1)).registerWorkerState(any(), any(), any());
        verify(workerIdService, times(1)).backfillWithRetry("CERT-1");
    }

    @Test
    void existingCertificateSkipsCreationAndExplainsWhy() throws Exception {
        when(archiveService.employeeCertificateExists("CERT-1")).thenReturn(1L);

        Map<String, Object> out = register();

        assertEquals(false, out.get("employeeCreated"));
        assertTrue(String.valueOf(out.get("employeeCreateReason")).contains("已存在"));
        verify(archiveService, never()).createEmployee(any());
    }

    @Test
    void successfulCreationReturnsIdAndPassword() throws Exception {
        when(archiveService.employeeCertificateExists("CERT-1")).thenReturn(0L);
        when(archiveService.createEmployee(any())).thenReturn(Map.of(
                "id", "emp_1", "account", "13800000001", "initialPassword", "abc123"));

        Map<String, Object> out = register();

        assertEquals(true, out.get("employeeCreated"));
        assertEquals("emp_1", out.get("employeeId"));
        assertEquals("abc123", out.get("initialPassword"));
        assertFalse(out.containsKey("employeeCreateReason"));
    }

    @Test
    void unexpectedExceptionStillKeepsRegistrationOk() throws Exception {
        when(archiveService.employeeCertificateExists("CERT-1")).thenReturn(0L);
        when(archiveService.createEmployee(any())).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> out = register();

        assertEquals(true, out.get("ok"));
        assertEquals(false, out.get("employeeCreated"));
        assertTrue(String.valueOf(out.get("employeeCreateReason")).contains("IllegalStateException"));
        assertEquals("", out.get("employeeId"));
    }
}
