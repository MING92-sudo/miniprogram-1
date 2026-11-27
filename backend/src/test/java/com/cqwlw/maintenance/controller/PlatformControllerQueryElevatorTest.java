package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformSyncService;
import com.cqwlw.maintenance.service.PlatformWorkerIdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2.7 单梯查询：唯一归属校验 + 只投影标识字段（不返回联系人信息）。 */
class PlatformControllerQueryElevatorTest {

    private PlatformClient platformClient;
    private PlatformController controller;

    @BeforeEach
    void setUp() {
        platformClient = mock(PlatformClient.class);
        controller = new PlatformController(mock(PlatformSyncService.class), mock(PlatformReportService.class),
                platformClient, mock(AdminArchiveService.class),
                mock(PlatformWorkerIdService.class), mock(IdempotencyService.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> query(Map<String, Object> body) {
        return (Map<String, Object>) controller.queryElevator(body).getData();
    }

    @Test
    void uniqueMatchReturnsOnlyIdentifierFields() {
        when(platformClient.configured()).thenReturn(true);
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                Map.of("elevatorCode", "130421", "deviceCode", "DT-1", "registrationCode", "REG-1",
                        "factoryNumber", "FN-1", "elevatorAdministerPhone", "1582****892"),
                Map.of("elevatorCode", "999999", "deviceCode", "DT-9", "registrationCode", "REG-9",
                        "factoryNumber", "OTHER")));

        Map<String, Object> result = query(Map.of("factoryNumber", "FN-1"));

        assertEquals(true, result.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> elevator = (Map<String, Object>) result.get("elevator");
        assertEquals("130421", elevator.get("elevatorCode"));
        assertEquals("DT-1", elevator.get("deviceCode"));
        assertEquals("REG-1", elevator.get("registrationCode"));
        assertEquals("FN-1", elevator.get("factoryNumber"));
        assertFalse(elevator.containsKey("elevatorAdministerPhone"));
    }

    @Test
    void multipleMatchesFailClosed() {
        when(platformClient.configured()).thenReturn(true);
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                Map.of("elevatorCode", "A", "factoryNumber", "FN-1"),
                Map.of("elevatorCode", "B", "factoryNumber", "FN-1")));

        Map<String, Object> result = query(Map.of("factoryNumber", "FN-1"));

        assertEquals(false, result.get("found"));
        assertTrue(String.valueOf(result.get("reason")).contains("多条"), String.valueOf(result.get("reason")));
    }

    @Test
    void foreignRecordDoesNotCountAsMatch() {
        when(platformClient.configured()).thenReturn(true);
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                Map.of("elevatorCode", "999999", "factoryNumber", "OTHER")));

        Map<String, Object> result = query(Map.of("factoryNumber", "FN-1"));

        assertEquals(false, result.get("found"));
        assertEquals("平台未查询到该电梯", result.get("reason"));
    }
}
