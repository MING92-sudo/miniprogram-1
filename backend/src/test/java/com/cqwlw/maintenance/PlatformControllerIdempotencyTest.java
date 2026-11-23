package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.ApiResponse;
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
import org.mockito.Mockito;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 建档端点幂等接线：丢失响应后同键重试必须重放已存响应，不产生重复平台登记/建档。 */
class PlatformControllerIdempotencyTest {

    private PlatformClient platformClient;
    private AdminArchiveService archiveService;
    private PlatformWorkerIdService workerIdService;
    private IdempotencyService idempotencyService;
    private PlatformController controller;

    @BeforeEach
    void setUp() {
        platformClient = mock(PlatformClient.class);
        archiveService = mock(AdminArchiveService.class);
        workerIdService = mock(PlatformWorkerIdService.class);
        idempotencyService = mock(IdempotencyService.class);
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

    @SuppressWarnings("unchecked")
    private void stubReplay(Map<String, Object> replay) {
        // 模拟 executeExternal 契约：首次执行供应商，同键重试直接重放已存响应（阶段二语义）
        AtomicInteger runs = new AtomicInteger();
        when(idempotencyService.executeExternal(any(), any(), any(), any())).thenAnswer(inv -> {
            if (runs.getAndIncrement() == 0) {
                return ((Supplier<Object>) inv.getArgument(3)).get();
            }
            return replay;
        });
    }

    @Test
    void registerWorkerReplaysStoredResponseWithoutDuplicatePlatformCall() throws Exception {
        Map<String, Object> replay = Map.of("ok", true, "employeeId", "emp_1", "account", "13800000001");
        stubReplay(replay);
        when(platformClient.registerWorkerState(any(), any(), any())).thenReturn(Map.of());
        HttpServletRequest req = mock(HttpServletRequest.class);

        ApiResponse<Object> first = controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), "key-w1", req);
        ApiResponse<Object> second = controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), "key-w1", req);

        verify(platformClient, times(1)).registerWorkerState(any(), any(), any());
        assertEquals(replay, second.getData());
        verify(idempotencyService, times(2)).executeExternal(eq("key-w1"), any(), eq(req), any());
        assertEquals(0, first.getCode());
    }

    @Test
    void registerServiceReplaysStoredResponseWithoutDuplicatePlatformCall() throws Exception {
        Map<String, Object> replay = Map.of("ok", true);
        stubReplay(replay);
        when(platformClient.registerServiceState(any(), any(), any())).thenReturn(Map.of());
        HttpServletRequest req = mock(HttpServletRequest.class);

        controller.registerService("渝达物业", "ENT-1", "0", "2026-01-01", "2027-12-31", pdf(), "key-s1", req);
        ApiResponse<Object> second = controller.registerService("渝达物业", "ENT-1", "0", "2026-01-01",
                "2027-12-31", pdf(), "key-s1", req);

        verify(platformClient, times(1)).registerServiceState(any(), any(), any());
        assertEquals(replay, second.getData());
        verify(idempotencyService, times(2)).executeExternal(eq("key-s1"), any(), eq(req), any());
    }

    @Test
    void registerServiceNormalizesNonMapDataForReplaySafety() throws Exception {
        // 平台 data 为非对象（标量/数组）时也必须归一化为 Map，否则首调成功但该键重放永久 500
        Map<String, Object> replay = Map.of("ok", true, "data", "SCALAR");
        stubReplay(replay);
        when(platformClient.registerServiceState(any(), any(), any()))
                .thenReturn(Map.of("data", "SCALAR"));
        HttpServletRequest req = mock(HttpServletRequest.class);

        ApiResponse<Object> first = controller.registerService("渝达物业", "ENT-1", "0", "2026-01-01",
                "2027-12-31", pdf(), "key-s2", req);
        ApiResponse<Object> second = controller.registerService("渝达物业", "ENT-1", "0", "2026-01-01",
                "2027-12-31", pdf(), "key-s2", req);

        assertEquals(Map.of("ok", true, "data", "SCALAR"), first.getData());
        assertEquals(replay, second.getData());
        verify(platformClient, times(1)).registerServiceState(any(), any(), any());
    }

    @Test
    void registerWorkerWithoutKeyExecutesDirectlyEachTime() throws Exception {
        when(idempotencyService.executeExternal(isNull(), any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Object> s = (Supplier<Object>) inv.getArgument(3);
            return s.get();
        });
        when(platformClient.registerWorkerState(any(), any(), any())).thenReturn(Map.of());
        HttpServletRequest req = mock(HttpServletRequest.class);

        controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), null, req);
        controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), null, req);

        verify(platformClient, times(2)).registerWorkerState(any(), any(), any());
        verify(idempotencyService, times(2)).executeExternal(isNull(), any(), eq(req), any());
    }

    @Test
    void registerWorkerReplaySkipsDuplicateEmployeeCreation() throws Exception {
        Map<String, Object> replay = Map.of("ok", true, "employeeId", "emp_1", "account", "13800000001");
        stubReplay(replay);
        when(platformClient.registerWorkerState(any(), any(), any())).thenReturn(Map.of());
        when(archiveService.employeeCertificateExists("CERT-1")).thenReturn(0L);
        when(archiveService.createEmployee(any())).thenReturn(Map.of(
                "id", "emp_1", "account", "13800000001", "initialPassword", "abc123"));
        HttpServletRequest req = mock(HttpServletRequest.class);

        controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), "key-w2", req);
        controller.registerWorker("张三", "CERT-1", "2026-01-01", "2027-01-01",
                "13800000001", "0", pdf(), "key-w2", req);

        verify(archiveService, times(1)).createEmployee(any());
    }
}
