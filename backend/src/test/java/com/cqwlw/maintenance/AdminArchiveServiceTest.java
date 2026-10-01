package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.PhoneMutexService;
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

        service = new AdminArchiveService(companyMapper, useUnitMapper,
                employeeMapper, elevatorMapper,
                new PhoneMutexService(companyMapper, useUnitMapper, employeeMapper));
    }

    @Test
    void createUseUnitRejectsMutexConflictWithConflictsPayload() {
        Map<String, Object> result = null;
        BizException e = assertThrows(BizException.class, () -> service.createUseUnit(Map.of(
                "unitName", "新物业",
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
}
