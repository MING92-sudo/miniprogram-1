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
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 档案维护：1002 互斥冲突必须携带 data.conflicts[] 且不落库； */
class AdminArchiveServiceTest {

    private CompanyMapper companyMapper;
    private UseUnitMapper useUnitMapper;
    private EmployeeMapper employeeMapper;
    private ElevatorMapper elevatorMapper;
    private PlatformClient platformClient;
    private com.cqwlw.maintenance.mapper.SysOrgMapper sysOrgMapper;
    private com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;
    private com.cqwlw.maintenance.mapper.MaintainRecordMapper maintainRecordMapper;
    private WorkOrderMapper workOrderMapper;
    private com.cqwlw.maintenance.service.AuditLogService auditLogService;
    private AdminArchiveService service;

    @BeforeEach
    void setUp() {
        companyMapper = mock(CompanyMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        sysOrgMapper = mock(com.cqwlw.maintenance.mapper.SysOrgMapper.class);
        userRoleMapper = mock(com.cqwlw.maintenance.mapper.SysUserRoleMapper.class);
        clientAccountMapper = mock(com.cqwlw.maintenance.mapper.ClientAccountMapper.class);
        maintainRecordMapper = mock(com.cqwlw.maintenance.mapper.MaintainRecordMapper.class);
        workOrderMapper = mock(WorkOrderMapper.class);
        auditLogService = mock(com.cqwlw.maintenance.service.AuditLogService.class);

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
                employeeMapper, userRoleMapper, elevatorMapper,
                new PhoneMutexService(companyMapper, useUnitMapper, employeeMapper),
                workOrderMapper, platformClient, sysOrgMapper, auditLogService);
        service.setClientAccountMapper(clientAccountMapper);
        service.setMaintainRecordMapper(maintainRecordMapper);
    }

    @Test
    void disableAndResetInvalidateExistingSessions() {
        Employee disabled = new Employee();
        disabled.id = "emp_disable";
        Employee reset = new Employee();
        reset.id = "emp_reset";
        when(employeeMapper.selectById("emp_disable")).thenReturn(disabled);
        when(employeeMapper.selectById("emp_reset")).thenReturn(reset);

        service.setEmployeeEnabled("emp_disable", false);
        service.resetEmployeePassword("emp_reset", "newpass123");

        org.junit.jupiter.api.Assertions.assertAll(
                () -> verify(employeeMapper).bumpScopeVersion("emp_disable"),
                () -> verify(employeeMapper).bumpScopeVersion("emp_reset"));
    }

    private AdminArchiveService transactionalProxy(PlatformTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (AdminArchiveService) factory.getProxy();
    }

    @Test
    void employeeUpdatesRollbackWhenScopeVersionBumpFails() {
        Employee disabled = new Employee();
        disabled.id = "emp_disable_tx";
        Employee reset = new Employee();
        reset.id = "emp_reset_tx";
        when(employeeMapper.selectById("emp_disable_tx")).thenReturn(disabled);
        when(employeeMapper.selectById("emp_reset_tx")).thenReturn(reset);
        when(employeeMapper.bumpScopeVersion(anyString()))
                .thenThrow(new IllegalStateException("scope write failed"));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> proxy.setEmployeeEnabled("emp_disable_tx", false)),
                () -> assertThrows(IllegalStateException.class,
                        () -> proxy.resetEmployeePassword("emp_reset_tx", "newpass123")));

        verify(employeeMapper).updateById(disabled);
        verify(employeeMapper).updateById(reset);
        verify(transactionManager, times(2)).rollback(any(TransactionStatus.class));
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void elevatorAdminListBatchLoadsUnitsAndRegions() {
        Elevator first = new Elevator();
        first.id = "el_1";
        first.elevatorCode = "EM-1";
        first.useUnitId = "uu_1";
        first.regionId = "r1";
        first.status = "INACTIVE";
        Elevator second = new Elevator();
        second.id = "el_2";
        second.elevatorCode = "EM-2";
        second.useUnitId = "uu_2";
        second.regionId = "r2";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(first, second));
        UseUnit one = new UseUnit(); one.id = "uu_1"; one.unitName = "单位一";
        UseUnit two = new UseUnit(); two.id = "uu_2"; two.unitName = "单位二";
        when(useUnitMapper.selectList(any())).thenReturn(List.of(one, two));
        com.cqwlw.maintenance.mapper.RegionMapper regions = mock(com.cqwlw.maintenance.mapper.RegionMapper.class);
        com.cqwlw.maintenance.entity.Region r1 = new com.cqwlw.maintenance.entity.Region(); r1.id = "r1"; r1.name = "区域一";
        com.cqwlw.maintenance.entity.Region r2 = new com.cqwlw.maintenance.entity.Region(); r2.id = "r2"; r2.name = "区域二";
        when(regions.selectList(any())).thenReturn(List.of(r1, r2));
        service.setRegionMapper(regions);

        List<Map<String, Object>> rows = service.elevatorAdminList();

        assertEquals(List.of("el_1", "el_2"), rows.stream().map(r -> r.get("id")).toList());
        assertEquals("单位一", rows.get(0).get("useUnitName"));
        assertEquals("区域二", rows.get(1).get("regionName"));
        assertEquals("INACTIVE", rows.get(0).get("status"));
        assertFalse(((List<?>) rows.get(0).get("missingRequired")).isEmpty());
        verify(useUnitMapper, times(1)).selectList(any());
        verify(useUnitMapper, never()).selectById(anyString());
        verify(regions, times(1)).selectList(any());
        verify(regions, never()).selectById(anyString());
    }

    @Test
    void elevatorAdminListSkipsEmptyReferenceQueries() {
        Elevator first = new Elevator();
        first.id = "el_1";
        first.elevatorCode = "EM-1";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(first));

        List<Map<String, Object>> rows = service.elevatorAdminList();

        assertEquals("", rows.get(0).get("useUnitName"));
        assertEquals("", rows.get(0).get("regionName"));
        verify(useUnitMapper, never()).selectById(anyString());
        verify(useUnitMapper, never()).selectList(any());
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
                "elevatorAdminister", "韩梅梅",
                "elevatorAdministerPhone", "13900002222",
                "emergencyPhone", "13800000001" // 应急电话不参与互斥
        ));
        ArgumentCaptor<UseUnit> captor = ArgumentCaptor.forClass(UseUnit.class);
        verify(useUnitMapper).insert(captor.capture());
        assertNotEquals("", captor.getValue().id);
        assertEquals("新物业", row.get("unitName"));
    }

    @Test
    void createUseUnitRejectsMissingReportContacts() {
        BizException e = assertThrows(BizException.class,
                () -> service.createUseUnit(Map.of("unitName", "新物业")));
        assertEquals(422, e.getCode());
        verify(useUnitMapper, never()).insert(any(UseUnit.class));
    }

    @Test
    void createUseUnitRejectsInvalidMobile() {
        BizException e = assertThrows(BizException.class, () -> service.createUseUnit(Map.of(
                "unitName", "新物业", "unitPrincipal", "李雷", "unitPrincipalPhone", "12345",
                "elevatorAdminister", "韩梅梅", "elevatorAdministerPhone", "13900002222",
                "emergencyPhone", "023-12345678")));
        assertEquals(422, e.getCode());
        verify(useUnitMapper, never()).insert(any(UseUnit.class));
    }

    @Test
    void updateUseUnitRejectsClearingReportContact() {
        UseUnit u = new UseUnit();
        u.id = "uu_1";
        u.unitName = "新物业";
        u.unitPrincipal = "李雷";
        u.unitPrincipalPhone = "13900001111";
        u.elevatorAdminister = "韩梅梅";
        u.elevatorAdministerPhone = "13900002222";
        u.emergencyPhone = "023-12345678";
        when(useUnitMapper.selectById("uu_1")).thenReturn(u);

        BizException e = assertThrows(BizException.class,
                () -> service.updateUseUnit("uu_1", Map.of("unitPrincipal", "")));

        assertEquals(422, e.getCode());
        verify(useUnitMapper, never()).updateById(any(UseUnit.class));
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
    void createEmployeeRejectsDuplicatePlatformId() {
        when(employeeMapper.selectCount(any())).thenReturn(0L, 1L);
        BizException e = assertThrows(BizException.class, () -> service.createEmployee(Map.of(
                "name", "甲", "phone", "13900003333", "role", "WORKER", "platformId", "pid_dup")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("平台人员ID"));
        verify(employeeMapper, never()).insert(any(Employee.class));
    }

    @Test
    void createEmployeeDefaultsBlankPlatformIdToNullAndAccountToPhone() {
        service.createEmployee(Map.of("name", "甲", "phone", "13900003333", "role", "WORKER"));
        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper).insert(captor.capture());
        assertNull(captor.getValue().platformId);
        assertEquals("13900003333", captor.getValue().account);
    }

    @Test
    void createEmployeePreservesCustomAccount() {
        service.createEmployee(Map.of("name", "甲", "phone", "13900003333", "role", "WORKER",
                "account", "custom-account"));
        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper).insert(captor.capture());
        assertEquals("custom-account", captor.getValue().account);
    }

    @Test
    void createEmployeeRejectsInvalidMobile() {
        BizException e = assertThrows(BizException.class, () -> service.createEmployee(Map.of(
                "name", "甲", "phone", "12345", "role", "WORKER")));
        assertEquals(422, e.getCode());
        verify(employeeMapper, never()).insert(any(Employee.class));
    }

    @Test
    void updateEmployeeRejectsDuplicatePlatformId() {
        Employee e = new Employee();
        e.id = "emp_dup";
        e.name = "甲";
        e.phone = "13900003333";
        e.role = "WORKER";
        when(employeeMapper.selectById("emp_dup")).thenReturn(e);
        when(employeeMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.updateEmployee("emp_dup", Map.of("platformId", "pid_dup"), "emp_admin"));

        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    @Test
    void updateEmployeeBlankPlatformIdClearsExplicitly() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Employee.class);
        Employee e = new Employee();
        e.id = "emp_clear";
        e.name = "甲";
        e.phone = "13900003333";
        e.role = "WORKER";
        e.platformId = "pid_old";
        when(employeeMapper.selectById("emp_clear")).thenReturn(e);

        service.updateEmployee("emp_clear", Map.of("platformId", ""), "emp_admin");

        assertNull(e.platformId);
        verify(employeeMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void deleteElevatorLocksRowBeforeInflightCheck() {
        Elevator elevator = new Elevator();
        elevator.id = "el_delete_lock";
        when(elevatorMapper.selectById(elevator.id)).thenReturn(elevator);
        when(elevatorMapper.selectByIdForUpdate(elevator.id)).thenReturn(elevator);
        when(workOrderMapper.selectCount(any())).thenReturn(0L);

        service.deleteElevator(elevator.id);

        InOrder order = inOrder(elevatorMapper, workOrderMapper);
        order.verify(elevatorMapper).selectByIdForUpdate(elevator.id);
        order.verify(workOrderMapper).selectCount(any());
        order.verify(elevatorMapper).deleteById(elevator.id);
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
    void updateElevatorTracksManualGeoSource() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.geoStatus = "SELF_COLLECTED";
        el.lng = new java.math.BigDecimal("106.1");
        el.lat = new java.math.BigDecimal("29.2");
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        service.updateElevator("el_9", Map.of("lng", "106.55", "lat", "29.56"));
        assertEquals("PROVIDED", el.geoStatus);
        service.updateElevator("el_9", Map.of("lng", "", "lat", ""));
        assertEquals("UNKNOWN", el.geoStatus);
    }

    @Test
    void updateElevatorRollsBackWhenExplicitClearFails() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Elevator.class);
        Elevator elevator = new Elevator();
        elevator.id = "el_clear_tx";
        elevator.elevatorCode = "EM-CLEAR-TX";
        elevator.elevatorName = "原名称";
        when(elevatorMapper.selectById(elevator.id)).thenReturn(elevator);
        when(elevatorMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenThrow(new IllegalStateException("clear failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.updateElevator(elevator.id,
                Map.of("elevatorName", "新名称", "lng", "", "lat", "")));

        verify(elevatorMapper).updateById(elevator);
        verifyRollback(transactionManager);
    }

    @Test
    void batchGeoUsesManualSourceAndRangeValidation() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-1";
        el.geoStatus = "SELF_COLLECTED";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        Map<String, Object> out = service.batchGeo(Map.of("items", List.of(
                Map.of("code", "EM-1", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "EM-1", "lng", "181", "lat", "29.56"))));
        assertEquals("PROVIDED", el.geoStatus);
        assertEquals(1, ((List<?>) out.get("failed")).size());
    }

    @Test
    void updateElevatorRejectsIncompleteGeoWithoutWriting() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-1";
        el.elevatorName = "测试梯";
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        BizException e = assertThrows(BizException.class,
                () -> service.updateElevator("el_9", Map.of("lng", "106.55")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("同时填写或同时留空"), e.getMessage());
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        verify(elevatorMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateElevatorRejectsOutOfRangeGeoWithoutWriting() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-1";
        el.elevatorName = "测试梯";
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        BizException badLng = assertThrows(BizException.class,
                () -> service.updateElevator("el_9", Map.of("lng", "180.000001", "lat", "29.56")));
        assertEquals(422, badLng.getCode());
        assertTrue(badLng.getMessage().contains("经度"), badLng.getMessage());
        BizException badLat = assertThrows(BizException.class,
                () -> service.updateElevator("el_9", Map.of("lng", "106.55", "lat", "-90.000001")));
        assertEquals(422, badLat.getCode());
        assertTrue(badLat.getMessage().contains("纬度"), badLat.getMessage());
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        verify(elevatorMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateElevatorRejectsMalformedGeoAsBusinessErrorWithoutWriting() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-1";
        el.elevatorName = "测试梯";
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        BizException e = assertThrows(BizException.class,
                () -> service.updateElevator("el_9", Map.of("lng", "abc", "lat", "29.56")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("经度格式不正确"), e.getMessage());
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        verify(elevatorMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void createElevatorRejectsInvalidGeoBeforeInsert() {
        BizException e = assertThrows(BizException.class, () -> service.createElevator(Map.of(
                "elevatorCode", "EM-9", "deviceCode", "DT-9", "registrationCode", "REG-9",
                "insideNumber", "KT-9", "factoryNumber", "FN-9",
                "lng", "106.55", "lat", "91")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("纬度"), e.getMessage());
        verify(elevatorMapper, never()).insert(any(Elevator.class));
    }

    /** 恢复在保必须显式 SET NULL（updateById 跳过 null 的回归锁） */
    @Test
    void updateElevatorSuspendAndResumeStatus() {
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
        when(elevatorMapper.selectById("el_9")).thenReturn(el);

        service.updateElevator("el_9", Map.of("status", "INACTIVE"));
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE", el.status);
        verify(elevatorMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));

        service.updateElevator("el_9", Map.of("status", "ACTIVE"));
        org.junit.jupiter.api.Assertions.assertNull(el.status);
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
                "elevatorCode", "EM-9", "deviceCode", "DT-9", "registrationCode", "REG-9",
                "insideNumber", "KT-9", "factoryNumber", "FN-9",
                "projectName", "测试梯", "useUnitId", "uu_none")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("使用单位不存在"), e.getMessage());
    }

    /** 设备信息档案：5 项必传（电梯编码/设备代码/注册代码/单位内编号/出厂编号），缺失即 422 且一次列全 */
    @Test
    void createElevatorRequiresFiveDeviceFields() {
        BizException e = assertThrows(BizException.class, () -> service.createElevator(Map.of(
                "elevatorCode", "EM-9", "projectName", "测试梯")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("设备代码"), e.getMessage());
        assertTrue(e.getMessage().contains("注册代码"), e.getMessage());
        assertTrue(e.getMessage().contains("单位内编号"), e.getMessage());
        assertTrue(e.getMessage().contains("出厂编号"), e.getMessage());
        // 已填的电梯编码不得被列为缺失项
        assertFalse(e.getMessage().contains("电梯编码"), e.getMessage());
        verify(elevatorMapper, never()).insert(any(Elevator.class));
    }

    /** 5 项齐备时允许创建；选填字段（项目名称/所在区域/下次年检日期）可为空 */
    @Test
    void createElevatorAcceptsRequiredOnly() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        Map<String, Object> row = service.createElevator(body);
        assertEquals("EM-9", row.get("elevatorCode"));
        assertEquals("REG-9", row.get("registrationCode"));
        assertTrue(((List<?>) row.get("missingRequired")).isEmpty());
        verify(elevatorMapper).insert(any(Elevator.class));
    }

    @Test
    void createElevatorPassesCertNoThrough() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        body.put("certNo", "梯11渝BSP8194(20)");
        Map<String, Object> row = service.createElevator(body);
        assertEquals("梯11渝BSP8194(20)", row.get("certNo"));
        // 未传时投影为空串而非 null
        body.remove("certNo");
        Map<String, Object> row2 = service.createElevator(body);
        assertEquals("", row2.get("certNo"));
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper, times(2)).insert(captor.capture());
        assertEquals("梯11渝BSP8194(20)", captor.getAllValues().get(0).certNo);
        assertNull(captor.getAllValues().get(1).certNo);
    }

    @Test
    void createElevatorDefaultsScheduleBaseline() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        Map<String, Object> row = service.createElevator(body);
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(captor.capture());
        Elevator saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertNotNull(saved.lastMaintenanceAt);
        assertEquals(saved.lastMaintenanceAt.toLocalDate().plusDays(15), saved.nextMaintenanceDate);
        assertFalse(String.valueOf(row.get("lastMaintenanceAt")).isEmpty());
        assertFalse(String.valueOf(row.get("nextMaintenanceDate")).isEmpty());
    }

    /** 建档即排期门禁：未传维保类型时缺省 HM（半月保），显式传入保留 */
    @Test
    void createElevatorDefaultsWorkTypeCodeToHm() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        service.createElevator(body);
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(captor.capture());
        assertEquals("HM", captor.getValue().workTypeCode);

        body.put("workTypeCode", "TM");
        service.createElevator(body);
        ArgumentCaptor<Elevator> captor2 = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper, times(2)).insert(captor2.capture());
        assertEquals("TM", captor2.getAllValues().get(1).workTypeCode);
    }

    /** 存量建档传真实上次维保日期（yyyy-MM-dd）→ 下次到期=该日+15；非法格式 422（基线错则年表全错，不静默吞） */
    @Test
    void createElevatorAcceptsExplicitBaselineAndRejectsGarbage() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        body.put("lastMaintenanceAt", "2026-01-10");
        service.createElevator(body);
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(captor.capture());
        Elevator saved = captor.getValue();
        assertEquals(java.time.LocalDateTime.of(2026, 1, 10, 0, 0), saved.lastMaintenanceAt);
        assertEquals(java.time.LocalDate.of(2026, 1, 25), saved.nextMaintenanceDate);

        Map<String, Object> bad = new java.util.HashMap<>(body);
        bad.put("lastMaintenanceAt", "去年一月");
        BizException e = assertThrows(BizException.class, () -> service.createElevator(bad));
        assertEquals(422, e.getCode());
    }

    /** 建档保存后触发单台 2.7 同步，返回行标注同步结果 */
    @Test
    void createElevatorSyncsSingleElevatorAfterInsert() {
        com.cqwlw.maintenance.service.PlatformSyncService sync =
                mock(com.cqwlw.maintenance.service.PlatformSyncService.class);
        service.setPlatformSyncService(sync);
        when(sync.configured()).thenReturn(true);
        when(sync.syncElevator(any(Elevator.class))).thenReturn(true);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        Map<String, Object> row = service.createElevator(body);

        assertEquals("OK", row.get("platformSync"));
        verify(sync).syncElevator(any(Elevator.class));
    }

    /** 凭证未配置时静默跳过（SKIPPED）；同步异常不阻断建档（FAILED） */
    @Test
    void createElevatorSyncSkippedOrFailedNeverBlocksCreate() {
        com.cqwlw.maintenance.service.PlatformSyncService sync =
                mock(com.cqwlw.maintenance.service.PlatformSyncService.class);
        service.setPlatformSyncService(sync);
        when(sync.configured()).thenReturn(false);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        Map<String, Object> row = service.createElevator(body);
        assertEquals("SKIPPED", row.get("platformSync"));
        verify(sync, never()).syncElevator(any(Elevator.class));

        when(sync.configured()).thenReturn(true);
        when(sync.syncElevator(any(Elevator.class))).thenThrow(new IllegalStateException("platform down"));
        Map<String, Object> row2 = service.createElevator(body);
        assertEquals("FAILED", row2.get("platformSync"));
        verify(elevatorMapper, times(2)).insert(any(Elevator.class));
    }

    /** 使用单位空串 = 清除关联：存 NULL 且编辑时显式 SET NULL（updateById 跳过 null 字段） */
    @Test
    void updateElevatorBlankUseUnitClearsExplicitly() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Elevator.class);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("elevatorCode", "EM-9");
        body.put("deviceCode", "DT-9");
        body.put("registrationCode", "REG-9");
        body.put("insideNumber", "KT-9");
        body.put("factoryNumber", "FN-9");
        body.put("useUnitId", "");
        service.createElevator(body);
        ArgumentCaptor<Elevator> captor = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(captor.capture());
        assertNull(captor.getValue().useUnitId);

        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-9";
        el.useUnitId = "uu_old";
        when(elevatorMapper.selectById("el_9")).thenReturn(el);
        service.updateElevator("el_9", Map.of("useUnitId", ""));
        assertNull(el.useUnitId);
        verify(elevatorMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /** 使用单位建档保存后自动查平台主体ID（2.2），返回行标注同步结果 */
    @Test
    void createUseUnitSyncsEntityIdAfterInsert() {
        com.cqwlw.maintenance.service.PlatformSyncService sync =
                mock(com.cqwlw.maintenance.service.PlatformSyncService.class);
        service.setPlatformSyncService(sync);
        when(sync.configured()).thenReturn(true);
        when(sync.syncUseUnitEntityId(any(UseUnit.class))).thenReturn(true);

        Map<String, Object> row = service.createUseUnit(Map.of(
                "unitName", "新物业",
                "organizationCode", "91500000TEST0001X",
                "unitPrincipal", "李雷",
                "unitPrincipalPhone", "13900001111",
                "elevatorAdminister", "韩梅梅",
                "elevatorAdministerPhone", "13900002222",
                "emergencyPhone", "023-12345678"));

        assertEquals("OK", row.get("platformSync"));
        verify(sync).syncUseUnitEntityId(any(UseUnit.class));
    }

    /** 凭证未配置跳过（SKIPPED）；平台未查到或异常均不阻断建档（NO_MATCH/FAILED） */
    @Test
    void createUseUnitSyncSkippedOrFailedNeverBlocksCreate() {
        com.cqwlw.maintenance.service.PlatformSyncService sync =
                mock(com.cqwlw.maintenance.service.PlatformSyncService.class);
        service.setPlatformSyncService(sync);
        when(sync.configured()).thenReturn(false);
        Map<String, Object> body = Map.of(
                "unitName", "新物业",
                "organizationCode", "91500000TEST0001X",
                "unitPrincipal", "李雷",
                "unitPrincipalPhone", "13900001111",
                "elevatorAdminister", "韩梅梅",
                "elevatorAdministerPhone", "13900002222",
                "emergencyPhone", "023-12345678");

        Map<String, Object> row = service.createUseUnit(body);
        assertEquals("SKIPPED", row.get("platformSync"));
        verify(sync, never()).syncUseUnitEntityId(any(UseUnit.class));

        when(sync.configured()).thenReturn(true);
        when(sync.syncUseUnitEntityId(any(UseUnit.class)))
                .thenThrow(new BizException(404, "平台未查询到该单位主体，请核对单位名称与统一社会信用代码"));
        Map<String, Object> row2 = service.createUseUnit(body);
        assertEquals("NO_MATCH", row2.get("platformSync"));

        when(sync.syncUseUnitEntityId(any(UseUnit.class)))
                .thenThrow(new IllegalStateException("platform down"));
        Map<String, Object> row3 = service.createUseUnit(body);
        assertEquals("FAILED", row3.get("platformSync"));
        verify(useUnitMapper, times(3)).insert(any(UseUnit.class));
    }

    /** 必传项不得清空：更新时传空串直接 422，避免设备档案与维保记录脱钩 */
    @Test
    void updateElevatorRejectsClearingRequiredField() {
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-9";
        el.deviceCode = "DT-9";
        el.regCode = "REG-9";
        el.insideNumber = "KT-9";
        el.factoryNumber = "FN-9";
        when(elevatorMapper.selectById("el_9")).thenReturn(el);
        BizException e = assertThrows(BizException.class,
                () -> service.updateElevator("el_9", Map.of("registrationCode", "")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("注册代码"), e.getMessage());
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
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

    @Test
    void updateCompanyRejectsIncompleteManager() {
        BizException e = assertThrows(BizException.class, () -> service.updateCompany(Map.of(
                "workMenegerName", "", "workMenegerPhone", "12345")));
        assertEquals(422, e.getCode());
        verify(companyMapper, never()).updateById(any(Company.class));
    }

    @Test
    void updateCompanyCreatesCompleteArchiveWhenMissing() {
        when(companyMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> row = service.updateCompany(Map.of(
                "name", "维保单位", "organizationCode", "91500000TEST0001X",
                "workMenegerName", "赵敏", "workMenegerPhone", "13900005555"));

        ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
        verify(companyMapper).insert(captor.capture());
        verify(companyMapper, never()).updateById(any(Company.class));
        assertEquals("赵敏", captor.getValue().workMenegerName);
        assertEquals("赵敏", row.get("workMenegerName"));
        assertNotEquals("", captor.getValue().id);
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


    /** 行投影带所属区域：regionId + 经 RegionMapper 解析的 regionName（未配置区域时为空串） */
    @Test
    void employeeRowIncludesRegion() {
        com.cqwlw.maintenance.mapper.RegionMapper regionMapper =
                mock(com.cqwlw.maintenance.mapper.RegionMapper.class);
        service.setRegionMapper(regionMapper);
        com.cqwlw.maintenance.entity.Region r = new com.cqwlw.maintenance.entity.Region();
        r.id = "reg_1";
        r.name = "渝北区";
        when(regionMapper.selectById("reg_1")).thenReturn(r);
        Employee e = new Employee();
        e.id = "emp_r1";
        e.name = "张三";
        e.role = "WORKER";
        e.regionId = "reg_1";
        when(employeeMapper.selectById("emp_r1")).thenReturn(e);

        Map<String, Object> row = service.setEmployeeEnabled("emp_r1", true);
        assertEquals("reg_1", row.get("regionId"));
        assertEquals("渝北区", row.get("regionName"));
    }

    /** 开通员工带 regionId：区域存在即落库 */
    @Test
    void createEmployeeAcceptsRegionId() {
        com.cqwlw.maintenance.mapper.RegionMapper regionMapper =
                mock(com.cqwlw.maintenance.mapper.RegionMapper.class);
        service.setRegionMapper(regionMapper);
        com.cqwlw.maintenance.entity.Region r = new com.cqwlw.maintenance.entity.Region();
        r.id = "reg_1";
        r.name = "渝北区";
        when(regionMapper.selectById("reg_1")).thenReturn(r);

        service.createEmployee(Map.of(
                "name", "丙", "phone", "13900005555", "role", "WORKER", "regionId", "reg_1"));
        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper).insert(captor.capture());
        assertEquals("reg_1", captor.getValue().regionId);
    }

    /** 开通员工带不存在区域：422「区域不存在」且不落库（校验失败不留半成品状态） */
    @Test
    void createEmployeeRejectsUnknownRegion() {
        com.cqwlw.maintenance.mapper.RegionMapper regionMapper =
                mock(com.cqwlw.maintenance.mapper.RegionMapper.class);
        service.setRegionMapper(regionMapper);
        when(regionMapper.selectById("reg_x")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.createEmployee(Map.of(
                "name", "丁", "phone", "13900006666", "role", "WORKER", "regionId", "reg_x")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("区域不存在"), e.getMessage());
        verify(employeeMapper, never()).insert(any(Employee.class));
    }

    /** 编辑员工传空串 regionId = 清除所属区域（null=不动，空串=清除，口径同电梯） */
    @Test
    void updateEmployeeBlankRegionClears() {
        // LambdaUpdateWrapper 显式 SET NULL 需 TableInfo 缓存（同 updateElevatorClearsGeoWhenBlankToRecollect）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Employee.class);
        Employee e = new Employee();
        e.id = "emp_r2";
        e.name = "王五";
        e.role = "WORKER";
        e.phone = "13900007777";
        e.regionId = "reg_1";
        when(employeeMapper.selectById("emp_r2")).thenReturn(e);

        service.updateEmployee("emp_r2", Map.of("regionId", ""), "emp_admin");
        org.junit.jupiter.api.Assertions.assertNull(e.regionId);
        // 锁定显式 SET NULL 清除路径：updateById 跳过 null 字段，缺这条 update(null, wrapper) 库中旧值清不掉
        verify(employeeMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ── 调人校验与审计──

    /** 目标班组必须是组织树中 status≠DISABLED 的 TEAM 节点名；不存在或已停用 → 422「目标班组不存在」且不落库 */
    @Test
    void updateEmployeeRejectsUnknownTeam() {
        Employee e = new Employee();
        e.id = "emp_t1";
        e.name = "赵六";
        e.role = "WORKER";
        e.phone = "13900001111";
        e.groupName = "维保一班";
        when(employeeMapper.selectById("emp_t1")).thenReturn(e);
        when(sysOrgMapper.selectList(any())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> service.updateEmployee("emp_t1", Map.of("groupName", "维保九十九组"), "emp_admin"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("目标班组不存在"), ex.getMessage());
        verify(employeeMapper, never()).updateById(any(Employee.class));

        // 同名但已停用（DISABLED）的班组同样拒绝
        com.cqwlw.maintenance.entity.SysOrg disabled = new com.cqwlw.maintenance.entity.SysOrg();
        disabled.id = "org_team_x";
        disabled.type = "TEAM";
        disabled.name = "维保九十九组";
        disabled.status = "DISABLED";
        when(sysOrgMapper.selectList(any())).thenReturn(List.of(disabled));
        BizException ex2 = assertThrows(BizException.class,
                () -> service.updateEmployee("emp_t1", Map.of("groupName", "维保九十九组"), "emp_admin"));
        assertEquals(422, ex2.getCode());
        assertTrue(ex2.getMessage().contains("目标班组不存在"), ex2.getMessage());
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    /** 目标班组存在于组织树（TEAM 且未停用）→ 落库成功 */
    @Test
    void updateEmployeeAcceptsExistingTeam() {
        com.cqwlw.maintenance.entity.SysOrg team = new com.cqwlw.maintenance.entity.SysOrg();
        team.id = "org_team_maint_2";
        team.type = "TEAM";
        team.name = "维保二组";
        team.status = "ACTIVE";
        when(sysOrgMapper.selectList(any())).thenReturn(List.of(team));

        Employee e = new Employee();
        e.id = "emp_t2";
        e.name = "钱七";
        e.role = "WORKER";
        e.phone = "13900002222";
        e.groupName = "维保一班";
        when(employeeMapper.selectById("emp_t2")).thenReturn(e);

        Map<String, Object> row = service.updateEmployee("emp_t2", Map.of("groupName", "维保二组"), "emp_admin");
        assertEquals("维保二组", row.get("groupName"));
        verify(employeeMapper).updateById(any(Employee.class));
    }

    /** 班组变更成功写审计：操作人、员工 id、原班组→新班组 */
    @Test
    void updateEmployeeTeamChangeWritesAudit() {
        com.cqwlw.maintenance.entity.SysOrg team = new com.cqwlw.maintenance.entity.SysOrg();
        team.id = "org_team_maint_2";
        team.type = "TEAM";
        team.name = "维保二组";
        team.status = "ACTIVE";
        when(sysOrgMapper.selectList(any())).thenReturn(List.of(team));

        Employee e = new Employee();
        e.id = "emp_t3";
        e.name = "孙八";
        e.role = "WORKER";
        e.phone = "13900003333";
        e.groupName = "维保一班";
        when(employeeMapper.selectById("emp_t3")).thenReturn(e);

        service.updateEmployee("emp_t3", Map.of("groupName", "维保二组"), "emp_admin_1");

        ArgumentCaptor<com.cqwlw.maintenance.entity.SysAuditLog> captor =
                ArgumentCaptor.forClass(com.cqwlw.maintenance.entity.SysAuditLog.class);
        verify(auditLogService).append(captor.capture());
        com.cqwlw.maintenance.entity.SysAuditLog entry = captor.getValue();
        assertEquals("emp_admin_1", entry.actorId);
        assertEquals("emp_t3", entry.target);
        assertTrue(entry.reason.contains("维保一班"), entry.reason);
        assertTrue(entry.reason.contains("维保二组"), entry.reason);
    }

    @Test
    void createEmployeeRollsBackWhenPrimaryRoleInsertFails() {
        when(userRoleMapper.insert(any(com.cqwlw.maintenance.entity.SysUserRole.class)))
                .thenThrow(new IllegalStateException("role insert failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.createEmployee(Map.of(
                "name", "事务测试", "phone", "13900008801", "role", "WORKER")));

        verify(employeeMapper).insert(any(Employee.class));
        verifyRollback(transactionManager);
    }

    @Test
    void updateEmployeeRollsBackWhenExplicitNullClearFails() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Employee.class);
        Employee employee = employee("emp_clear_tx", "13900008802");
        employee.platformId = "pid_old";
        when(employeeMapper.selectById(employee.id)).thenReturn(employee);
        when(employeeMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenThrow(new IllegalStateException("clear failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class,
                () -> proxy.updateEmployee(employee.id, Map.of("platformId", ""), "emp_admin"));

        verify(employeeMapper).updateById(employee);
        verifyRollback(transactionManager);
    }

    @Test
    void updateEmployeeRollsBackWhenPrimaryRoleInsertFails() {
        Employee employee = employee("emp_role_tx", "13900008803");
        when(employeeMapper.selectById(employee.id)).thenReturn(employee);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(userRoleMapper.insert(any(com.cqwlw.maintenance.entity.SysUserRole.class)))
                .thenThrow(new IllegalStateException("role insert failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class,
                () -> proxy.updateEmployee(employee.id, Map.of("role", "MAINT_SUP"), "emp_admin"));

        verify(employeeMapper).updateById(employee);
        verifyRollback(transactionManager);
    }

    @Test
    void deleteUseUnitRollsBackWhenRecordDetachFails() {
        prepareUnitDeletion("uu_detach_tx");
        when(maintainRecordMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenThrow(new IllegalStateException("detach failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.deleteUseUnit("uu_detach_tx"));

        verifyRollback(transactionManager);
    }

    @Test
    void deleteUseUnitRollsBackWhenClientAccountDeleteFails() {
        prepareUnitDeletion("uu_account_tx");
        when(clientAccountMapper.delete(any())).thenThrow(new IllegalStateException("account delete failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.deleteUseUnit("uu_account_tx"));

        verify(maintainRecordMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyRollback(transactionManager);
    }

    @Test
    void deleteUseUnitRollsBackWhenUnitDeleteFails() {
        prepareUnitDeletion("uu_delete_tx");
        when(useUnitMapper.deleteById("uu_delete_tx")).thenThrow(new IllegalStateException("unit delete failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.deleteUseUnit("uu_delete_tx"));

        verify(clientAccountMapper).delete(any());
        verifyRollback(transactionManager);
    }

    @Test
    void batchGeoRollsBackWhenLaterUpdateFails() {
        Elevator first = elevatorWithCode("el_geo_tx_1", "EM-TX-1");
        Elevator second = elevatorWithCode("el_geo_tx_2", "EM-TX-2");
        when(elevatorMapper.selectList(any())).thenReturn(List.of(first), List.of(second));
        when(elevatorMapper.updateById(any(Elevator.class))).thenReturn(1)
                .thenThrow(new IllegalStateException("second geo update failed"));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.batchGeo(Map.of("items", List.of(
                Map.of("code", "EM-TX-1", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "EM-TX-2", "lng", "106.56", "lat", "29.57")))));

        verifyRollback(transactionManager);
    }

    @Test
    void batchGeoBusinessFailuresKeepPartialSuccess() {
        Elevator elevator = elevatorWithCode("el_geo_partial", "EM-PARTIAL");
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        Map<String, Object> result = proxy.batchGeo(Map.of("items", List.of(
                Map.of("code", "EM-PARTIAL", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "EM-PARTIAL", "lng", "181", "lat", "29.56"))));

        assertEquals(1, ((List<?>) result.get("success")).size());
        assertEquals(1, ((List<?>) result.get("failed")).size());
        verifyCommit(transactionManager);
    }

    @Test
    void groupAuditFailureDoesNotRollbackEmployeeUpdate() {
        com.cqwlw.maintenance.entity.SysOrg team = new com.cqwlw.maintenance.entity.SysOrg();
        team.type = "TEAM";
        team.name = "维保二组";
        team.status = "ACTIVE";
        when(sysOrgMapper.selectList(any())).thenReturn(List.of(team));
        Employee employee = employee("emp_audit_tx", "13900008804");
        employee.groupName = "维保一班";
        when(employeeMapper.selectById(employee.id)).thenReturn(employee);
        doThrow(new IllegalStateException("audit failed")).when(auditLogService)
                .append(any(com.cqwlw.maintenance.entity.SysAuditLog.class));
        PlatformTransactionManager transactionManager = newTransactionManager();
        AdminArchiveService proxy = transactionalProxy(transactionManager);

        Map<String, Object> result = proxy.updateEmployee(
                employee.id, Map.of("groupName", "维保二组"), "emp_admin");

        assertEquals("维保二组", result.get("groupName"));
        verifyCommit(transactionManager);
    }

    private PlatformTransactionManager newTransactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return transactionManager;
    }

    private void verifyRollback(PlatformTransactionManager transactionManager) {
        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    private void verifyCommit(PlatformTransactionManager transactionManager) {
        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(transactionManager, never()).rollback(any(TransactionStatus.class));
    }

    private Employee employee(String id, String phone) {
        Employee employee = new Employee();
        employee.id = id;
        employee.name = "事务测试";
        employee.phone = phone;
        employee.role = "WORKER";
        return employee;
    }

    private Elevator elevatorWithCode(String id, String code) {
        Elevator elevator = new Elevator();
        elevator.id = id;
        elevator.elevatorCode = code;
        return elevator;
    }

    private void prepareUnitDeletion(String id) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.cqwlw.maintenance.entity.MaintainRecord.class);
        UseUnit unit = new UseUnit();
        unit.id = id;
        unit.unitName = "事务测试单位";
        when(useUnitMapper.selectById(id)).thenReturn(unit);
        when(elevatorMapper.selectCount(any())).thenReturn(0L);
    }
}
