package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.UseUnit;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(reportService.syncLegacy()).thenReturn(new PlatformReportService.SyncLegacyResult(0, 0));
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

    /** 2.5 的 workEndDate 是合同有效期下界：拉全量名单不得传，否则名单为空导致人员永不同步 */
    @Test
    void syncAllQueriesWorkersWithoutWorkEndDate() {
        service.syncAll();

        verify(platformClient, times(1)).queryWorkList(eq("0"), isNull());
    }

    /** 全量同步回填维保单位主体ID（2.2） */
    @Test
    void syncAllStoresCompanyEntityId() {
        Company c = new Company();
        c.id = "co_1";
        c.name = "重庆博威电梯有限公司";
        c.organizationCode = "91500106MAABU3795M";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));
        when(platformClient.queryEntity(anyString(), anyString()))
                .thenReturn(Map.of("entityID", "311715940563360", "unitName", "重庆博威电梯有限公司"));

        Map<String, Object> summary = service.syncAll();

        assertEquals(1, summary.get("entitySynced"));
        assertEquals("311715940563360", c.entityId);
        verify(companyMapper).updateById(c);
    }

    /** 主体回填 404 不得中断后续电梯/人员同步 */
    @Test
    void syncEntitiesFailureDoesNotAbortSyncAll() {
        Company c = new Company();
        c.id = "co_1";
        c.name = "博威电梯";
        c.organizationCode = "BAD-CODE";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));
        when(platformClient.queryEntity(anyString(), anyString()))
                .thenThrow(new BizException(404, "平台未查询到该单位主体"));

        Map<String, Object> summary = service.syncAll();

        assertEquals(0, summary.get("entitySynced"));
        verify(platformClient).queryWorkList(eq("0"), isNull());
        verify(companyMapper, never()).updateById(any(Company.class));
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
        // 本地没有可查询的出厂编号/注册代码/设备代码时，无法确认平台返回记录归属，必须跳过
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
        // 三项本地标识全空 → 无从确认归属，即便平台返回记录也不得写入
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
        assertEquals("13800000001", el.elevatorAdministerPhone);
    }

    /** 2.7 实测键名为 useUnitEntityID（大写 ID），规范示例误作 useUnitEntityId */
    @Test
    void syncElevatorReadsUppercaseUseUnitEntityIdKey() {
        Elevator el = localElevator();
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord(Map.of(
                "factoryNumber", "SGL20131212-1", "useUnitEntityID", "ent_9"))));

        assertTrue(service.syncElevator(el));
        assertEquals("ent_9", el.useUnitEntityId);
    }

    /** 电梯未手动指定使用单位时，按平台主体ID自动挂接本地使用单位档案 */
    @Test
    void syncElevatorAutoLinksLocalUseUnitByEntityId() {
        Elevator el = localElevator();
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord(Map.of(
                "factoryNumber", "SGL20131212-1", "useUnitEntityID", "ent_9"))));
        UseUnit uu = new UseUnit();
        uu.id = "uu_9";
        uu.entityId = "ent_9";
        when(useUnitMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(List.of(uu));

        assertTrue(service.syncElevator(el));
        assertEquals("uu_9", el.useUnitId);
    }

    /** 已手动指定使用单位的电梯不被 2.7 挂接覆盖 */
    @Test
    void syncElevatorKeepsManuallyAssignedUseUnit() {
        Elevator el = localElevator();
        el.useUnitId = "uu_manual";
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord(Map.of(
                "factoryNumber", "SGL20131212-1", "useUnitEntityID", "ent_9"))));

        assertTrue(service.syncElevator(el));

        assertEquals("uu_manual", el.useUnitId);
        assertEquals("ent_9", el.useUnitEntityId);
        verify(useUnitMapper, never()).selectList(
                any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
    }

    /** 使用单位 2.2 主体ID回填：单位名称+统一社会信用代码缺一即不查 */
    @Test
    void syncUseUnitEntityIdBackfillsAndSkipsBlank() {
        UseUnit u = new UseUnit();
        u.id = "uu_1";
        u.unitName = "某物业";
        u.organizationCode = "91500000TEST0001X";
        when(platformClient.queryEntityId("91500000TEST0001X", "某物业")).thenReturn("ent_1");

        assertTrue(service.syncUseUnitEntityId(u));
        assertEquals("ent_1", u.entityId);
        verify(useUnitMapper).updateById(u);

        UseUnit noOrg = new UseUnit();
        noOrg.unitName = "某物业";
        assertFalse(service.syncUseUnitEntityId(noOrg));
        UseUnit noName = new UseUnit();
        noName.organizationCode = "91500000TEST0001X";
        assertFalse(service.syncUseUnitEntityId(noName));
        verify(platformClient, times(1)).queryEntityId(any(), any());
    }
}
