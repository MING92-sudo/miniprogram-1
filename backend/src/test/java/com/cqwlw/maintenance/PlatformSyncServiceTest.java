package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformSyncService;
import com.cqwlw.maintenance.service.PlatformTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2.7 平台档案同步归属校验（NB2）：
 * 平台按条件查询可能返回多台梯，原实现盲取 list.get(0)，会把**别的电梯**的 elevatorCode
 * 写成本梯的；该字段是 2.7 查询与派单的身份键，写错后同步与派单都会落到别的梯上。
 * 故无法确认归属时必须 fail-closed 跳过。
 */
class PlatformSyncServiceTest {

    private CompanyMapper companyMapper;
    private UseUnitMapper useUnitMapper;
    private ElevatorMapper elevatorMapper;
    private PlatformClient platformClient;
    private PlatformTokenService tokenService;
    private EmployeeMapper employeeMapper;
    private PlatformReportService reportService;
    private PlatformSyncService service;

    @BeforeEach
    void setUp() {
        companyMapper = mock(CompanyMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        platformClient = mock(PlatformClient.class);
        tokenService = mock(PlatformTokenService.class);
        employeeMapper = mock(EmployeeMapper.class);
        reportService = mock(PlatformReportService.class);
        service = new PlatformSyncService(companyMapper, useUnitMapper, elevatorMapper,
                platformClient, tokenService, employeeMapper, reportService);
        when(tokenService.configured()).thenReturn(true);
        when(companyMapper.selectList(any())).thenReturn(List.of());
        when(useUnitMapper.selectList(any())).thenReturn(List.of());
        when(employeeMapper.selectList(any())).thenReturn(List.of());
    }

    private Elevator localElevator() {
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorCode = "130421";
        el.factoryNumber = "SGL20131212-1";
        el.regCode = "31105001062011050006";
        el.deviceCode = "DEV-1";
        el.elevatorAdministerPhone = "13800000001";
        return el;
    }

    private Map<String, Object> platformRecord(Map<String, Object> fields) {
        return new LinkedHashMap<>(fields);
    }

    @Test
    void foreignRecordDoesNotOverwriteElevatorCode() {
        Elevator el = localElevator();
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                platformRecord(Map.of("elevatorCode", "999999", "factoryNumber", "OTHER-FN-9"))));

        Map<String, Object> summary = service.syncAll();

        assertEquals(0, summary.get("elevatorSynced"));
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        assertEquals("130421", el.elevatorCode);
        assertNull(el.platformSyncedAt);
    }

    @Test
    void foreignRecordBeforeOwnRecordStillPicksOwnOne() {
        // 平台可能把不匹配的记录排在前面；必须挑出能确认的那条，而不是第一或最后一条
        Elevator el = localElevator();
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                platformRecord(Map.of("elevatorCode", "999999", "factoryNumber", "OTHER-FN-9")),
                platformRecord(Map.of("elevatorCode", "130421", "factoryNumber", "SGL20131212-1"))));

        assertEquals(1, service.syncAll().get("elevatorSynced"));
        assertEquals("130421", el.elevatorCode);
    }

    @Test
    void onlyElevatorCodeIdentityRejectsDifferentPlatformCode() {
        // 本地只有 elevatorCode 可查（2.7 参数表不含该字段，待平台确认 #8）：
        // 平台回的是**别的梯**的编码时必须跳过，否则本地编码被改写即身份错位
        Elevator el = new Elevator();
        el.id = "el_2";
        el.elevatorCode = "130421";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                platformRecord(Map.of("elevatorCode", "888888"))));

        assertEquals(0, service.syncAll().get("elevatorSynced"));
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        assertEquals("130421", el.elevatorCode);
    }

    @Test
    void allIdentifiersBlankSkipsSync() {
        // 四项本地标识全空 → 无从确认归属，即便平台返回记录也不得写入
        Elevator el = new Elevator();
        el.id = "el_3";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                platformRecord(Map.of("elevatorCode", "130421", "factoryNumber", "SGL-1"))));

        assertEquals(0, service.syncAll().get("elevatorSynced"));
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        assertNull(el.elevatorCode);
    }

    @Test
    void matchedRecordSyncsAndKeepsLocalRealPhone() {
        Elevator el = localElevator();
        el.elevatorCode = "OLD-CODE";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(
                platformRecord(Map.of(
                        "elevatorCode", "130421",
                        "factoryNumber", "SGL20131212-1",
                        "useUnitEntityId", "5633318206815862786",
                        "elevatorAdministerPhone", "1582****892",
                        "emergencyPhone", "400****588",
                        "elevatorAdminister", "张三"))));

        assertEquals(1, service.syncAll().get("elevatorSynced"));
        verify(elevatorMapper).updateById(el);
        assertEquals("130421", el.elevatorCode);
        assertEquals("5633318206815862786", el.useUnitEntityId);
        assertEquals("张三", el.elevatorAdminister);
        // docs/04 B.7：平台手机号为脱敏值，不得覆盖本地真实号码
        assertEquals("13800000001", el.elevatorAdministerPhone);
    }
}