package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.PhoneMutexService;
import com.cqwlw.maintenance.service.PlatformClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案维护（docs/09 一期）：1002 互斥冲突必须携带 data.conflicts[] 且不落库；
 * 角色白名单与账号唯一性在服务端校验（docs/09 决策 #3）。
 */
class AdminArchiveServiceTest {

    private CompanyMapper companyMapper;
    private UseUnitMapper useUnitMapper;
    private EmployeeMapper employeeMapper;
    private ElevatorMapper elevatorMapper;
    private PlatformClient platformClient;
    private AdminArchiveService service;

    @BeforeEach
    void setUp() {
        companyMapper = mock(CompanyMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);

        Company c = new Company();
        c.id = "co_1";
        c.name = "维保单位";
        c.workMenegerPhone = "13700000001";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));

        Employee worker = new Employee();
        worker.id = "emp_1";
        worker.name = "张伟";
        worker.role = "WORKER";
        worker.phone = "13800000001";
        when(employeeMapper.selectList(any())).thenReturn(List.of(worker));
        when(employeeMapper.selectCount(any())).thenReturn(0L);

        UseUnit other = new UseUnit();
        other.id = "uu_2";
        other.unitName = "其他物业";
        other.unitPrincipalPhone = "13930003002";
        other.elevatorAdministerPhone = "13800003005";
        when(useUnitMapper.selectList(any())).thenReturn(List.of(other));

        platformClient = mock(PlatformClient.class);
        service = new AdminArchiveService(companyMapper, useUnitMapper,
                employeeMapper, elevatorMapper,
                new PhoneMutexService(companyMapper, useUnitMapper, employeeMapper),
                mock(WorkOrderMapper.class), platformClient);
    }

    @Test
    void createUseUnitRejectsMutexConflictWithConflictsPayload() {
        Map<String, Object> result = null;
        BizException e = assertThrows(BizException.class, () -> service.createUseUnit(Map.of(
                "unitName", "新物业",
                "organizationCode", "91500000TEST0001X",
                "unitPrincipalPhone", "13930003002",   // 与既有使用单位负责人同号
                "elevatorAdministerPhone", "13800000001" // 与维保人员同号
        )));
        assertEquals(1002, e.getCode());
        // 负责人与其他单位负责人同号=同角色不算冲突；安全管理员撞维保人员 → 1 组冲突
        assertEquals(1, ((List<?>) e.getData().get("conflicts")).size());
        verify(useUnitMapper, never()).insert(any(UseUnit.class));
    }

    @Test
    void createUseUnitHappyPathInserts() {
        Map<String, Object> row = service.createUseUnit(Map.of(
                "unitName", "新物业",
                "organizationCode", "91500000TEST0001X",
                "unitPrincipal", "李雷",
                "unitPrincipalPhone", "13900001111",
                "elevatorAdministerPhone", "13900002222",
                "emergencyPhone", "13800000001" // 应急电话不参与互斥
        ));
        ArgumentCaptor<UseUnit> captor = ArgumentCaptor.forClass(UseUnit.class);
        verify(useUnitMapper).insert(captor.capture());
        assertNotEquals("", captor.getValue().id);
        assertEquals("新物业", row.get("unitName"));
    }

    @Test
    void createEmployeeValidatesRoleAndAccount() {
        BizException badRole = assertThrows(BizException.class, () -> service.createEmployee(Map.of(
                "name", "甲", "phone", "13900003333", "role", "UNIT_ADMIN")));
        assertEquals(422, badRole.getCode());

        when(employeeMapper.selectCount(any())).thenReturn(1L);
        BizException dup = assertThrows(BizException.class, () -> service.createEmployee(Map.of(
                "name", "乙", "phone", "13900004444", "role", "WORKER")));
        assertEquals(422, dup.getCode());
        assertTrue(dup.getMessage().contains("账号已存在"));
    }

    @Test
    void updateElevatorClearsGeoWhenBlankToRecollect() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Elevator.class);
        Employee worker = new Employee();
        worker.id = "emp_1";
        worker.role = "WORKER";
        when(employeeMapper.selectList(any())).thenReturn(List.of(worker));

        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-1";
        el.elevatorName = "测试梯";
        el.lng = new java.math.BigDecimal("106.1");
        el.lat = new java.math.BigDecimal("29.2");
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        service.updateElevator("el_9", Map.of("elevatorName", "测试梯", "lng", "", "lat", ""));
        verify(elevatorMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void deleteEmployeeBlockedWhilePlatformBindingActive() {
        Employee e = new Employee();
        e.id = "emp_9";
        e.name = "已登记";
        e.role = "WORKER";
        e.platformId = "6901282369105174537";
        e.bindStatus = 0;
        when(employeeMapper.selectById("emp_9")).thenReturn(e);
        BizException blocked = assertThrows(BizException.class, () -> service.deleteEmployee("emp_9"));
        assertEquals(422, blocked.getCode());
        verify(employeeMapper, never()).deleteById(anyString());

        e.bindStatus = 1;
        service.deleteEmployee("emp_9");
        verify(employeeMapper).deleteById("emp_9");
    }

    @Test
    void createElevatorRejectsUnknownUseUnit() {
        when(useUnitMapper.selectById("uu_none")).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.createElevator(Map.of(
                "elevatorCode", "EM-9", "elevatorName", "测试梯", "useUnitId", "uu_none")));
        assertEquals(422, e.getCode());
    }

    @Test
    void updateCompanyRunsMutexCheckOnManagerPhone() {
        when(companyMapper.updateById(any(Company.class))).thenReturn(1);
        Map<String, Object> row = service.updateCompany(Map.of(
                "workMenegerName", "赵敏", "workMenegerPhone", "13700000099"));
        assertEquals("13700000099", row.get("workMenegerPhone"));
        // 与维保人员同号 → 1002
        BizException e = assertThrows(BizException.class, () -> service.updateCompany(
                Map.of("workMenegerPhone", "13800000001")));
        assertEquals(1002, e.getCode());
    }

    // ── 范围收敛新增（docs/04 A.9.0 / docs/09 V3.3）──

    private Employee platformWorker(String id) {
        Employee e = new Employee();
        e.id = id;
        e.name = "张伟";
        e.role = "WORKER";
        e.phone = "13800000001";
        e.platformId = "6901282369105174537";
        return e;
    }

    @Test
    void batchAssignWorkersBindsElevatorAndWritesWorkerFields() {
        Employee principal = platformWorker("emp_9");
        when(employeeMapper.selectById("emp_9")).thenReturn(principal);
        Elevator el = new Elevator();
        el.id = "elv_1";
        el.elevatorCode = "DT001";
        el.elevatorName = "A栋1#梯";
        when(elevatorMapper.selectById("elv_1")).thenReturn(el);

        Map<String, Object> out = service.batchAssignWorkers(Map.of(
                "elevatorIds", List.of("elv_1"), "principalId", "emp_9"));

        assertEquals(1, ((List<?>) out.get("success")).size());
        assertEquals(0, ((List<?>) out.get("failed")).size());
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).updateById(captor.capture());
        assertEquals("6901282369105174537", captor.getValue().workerPlatformId);
        assertEquals("张伟", captor.getValue().workerName);
    }

    @Test
    void batchAssignWorkersMissingPlatformIdFailsPerElevator() {
        Employee noPlatform = platformWorker("emp_9");
        noPlatform.platformId = "";
        when(employeeMapper.selectById("emp_9")).thenReturn(noPlatform);

        Map<String, Object> out = service.batchAssignWorkers(Map.of(
                "elevatorIds", List.of("elv_1", "elv_2"), "principalId", "emp_9"));

        assertEquals(0, ((List<?>) out.get("success")).size());
        assertEquals(2, ((List<?>) out.get("failed")).size());
    }

    @Test
    void batchAssignWorkersSamePrincipalAndAssistantRejected() {
        when(employeeMapper.selectById("emp_9")).thenReturn(platformWorker("emp_9"));
        BizException e = assertThrows(BizException.class, () -> service.batchAssignWorkers(Map.of(
                "elevatorIds", List.of("elv_1"),
                "principalId", "emp_9", "assistantId", "emp_9")));
        assertEquals(1007, e.getCode());
    }

    @Test
    void batchAssignWorkersExpiredCertificateRejected() {
        Employee expired = platformWorker("emp_9");
        expired.workEndDate = "2020-01-01";
        when(employeeMapper.selectById("emp_9")).thenReturn(expired);
        when(elevatorMapper.selectById("elv_1")).thenReturn(new Elevator());

        Map<String, Object> out = service.batchAssignWorkers(Map.of(
                "elevatorIds", List.of("elv_1"), "principalId", "emp_9"));
        List<?> failed = (List<?>) out.get("failed");
        assertEquals(1, failed.size());
        assertTrue(String.valueOf(((Map<?, ?>) failed.get(0)).get("reason")).contains("证件已过期"));
    }

    @Test
    void batchGeoUpdatesByCodeAndReportsFailures() {
        Elevator el = new Elevator();
        el.id = "elv_1";
        el.elevatorCode = "DT001";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));

        Map<String, Object> out = service.batchGeo(Map.of("items", List.of(
                Map.of("code", "DT001", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "DT404", "lng", "1", "lat", "1"),
                Map.of("code", "DT001", "lng", "x", "lat", "1"))));

        // mock 对任何查询条件都返回同一电梯 → DT001/DT404 都命中，仅经纬度格式错的一条失败
        assertEquals(2, ((List<?>) out.get("success")).size());
        assertEquals(1, ((List<?>) out.get("failed")).size());
        assertTrue(((List<?>) out.get("success")).stream()
                .anyMatch(v -> "DT001".equals(((Map<?, ?>) v).get("code"))));
        verify(elevatorMapper, org.mockito.Mockito.times(2)).updateById(any(Elevator.class));
    }

    @Test
    void syncEntityIdFriendlyWhenPlatformNotConfigured() {
        Company withOrg = new Company();
        withOrg.id = "co_1";
        withOrg.name = "维保单位";
        withOrg.organizationCode = "5001ORGCODE";
        when(companyMapper.selectList(any())).thenReturn(List.of(withOrg));
        when(platformClient.configured()).thenReturn(false);
        Map<String, Object> out = service.syncCompanyEntityId();
        assertEquals(false, out.get("found"));
        assertEquals("监管平台凭证未配置", out.get("reason"));
    }
}
