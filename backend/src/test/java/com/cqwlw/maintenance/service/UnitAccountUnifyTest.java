package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V41 门户账号并入员工体系合并规则（M1，规格 §3.1 / 计划裁决 R2/R3）：
 * 同部门合并、跨部门不合并（R7）、密码优先、openid 不抢夺、空库 no-op。
 */
class UnitAccountUnifyTest {

    private UseUnitMapper units;
    private EmployeeMapper emps;
    private SysUserRoleMapper roles;
    private UnitAccountUnifyService svc;

    private void setUp(List<UseUnit> pending, Employee match) {
        units = mock(UseUnitMapper.class);
        emps = mock(EmployeeMapper.class);
        roles = mock(SysUserRoleMapper.class);
        when(units.selectList(any())).thenReturn(pending);
        when(emps.selectOne(any())).thenReturn(match);
        when(emps.selectCount(any())).thenReturn(0L);
        when(roles.selectCount(any())).thenReturn(0L);
        svc = new UnitAccountUnifyService(units, emps, roles);
    }

    private UseUnit unit() {
        UseUnit u = new UseUnit();
        u.id = "u1";
        u.unitName = "启元物业";
        u.account = "13800000000";
        u.passwordHash = "hash-unit";
        u.accountMigrated = 0;
        return u;
    }

    @Test
    void unitOnlyAccountCreatesEmployeeAndClientAdminRole() {
        setUp(List.of(unit()), null);
        assertEquals(1, svc.unifyPending());
        verify(emps).insert(any(Employee.class));
        verify(roles).insert(any(SysUserRole.class));
    }

    @Test
    void sameDepartmentMergesRolesWithoutNewEmployee() {
        Employee exist = new Employee();
        exist.id = "e1";
        exist.account = "13800000000";
        exist.department = "UNIT";
        exist.passwordHash = "hash-old";
        setUp(List.of(unit()), exist);
        assertEquals(1, svc.unifyPending());
        verify(emps, never()).insert(any(Employee.class));
        verify(roles).insert(any(SysUserRole.class));
        assertEquals("hash-old", exist.passwordHash); // 密码优先：不覆盖
    }

    @Test
    void crossDepartmentAccountIsNotMerged() {
        Employee maint = new Employee();
        maint.id = "e1";
        maint.account = "13800000000";
        maint.department = "MAINT";
        UseUnit u = unit();
        setUp(List.of(u), maint);
        assertEquals(0, svc.unifyPending());
        verify(emps, never()).insert(any(Employee.class));
        verify(roles, never()).insert(any(SysUserRole.class));
        assertEquals(0, u.accountMigrated); // R7 跨部门保留兜底
    }

    @Test
    void nullDepartmentTreatedAsMergeable() {
        Employee exist = new Employee();
        exist.id = "e1";
        exist.account = "13800000000";
        exist.department = null;
        setUp(List.of(unit()), exist);
        assertEquals(1, svc.unifyPending());
        verify(emps, never()).insert(any(Employee.class));
    }

    @Test
    void occupiedOpenidIsNotStolen() {
        UseUnit u = unit();
        u.openid = "o-shared";
        setUp(List.of(u), null);
        when(emps.selectCount(any())).thenReturn(1L); // openid 已被其他员工持有
        assertEquals(1, svc.unifyPending());
        verify(emps).insert(org.mockito.ArgumentMatchers.<Employee>argThat(e -> e.openid == null));
    }

    @Test
    void freeOpenidIsCopied() {
        UseUnit u = unit();
        u.openid = "o-free";
        setUp(List.of(u), null);
        assertEquals(1, svc.unifyPending());
        verify(emps).insert(org.mockito.ArgumentMatchers.<Employee>argThat(e -> "o-free".equals(e.openid)));
    }

    @Test
    void emptyPendingIsNoOp() {
        setUp(List.of(), null);
        assertEquals(0, svc.unifyPending());
        verify(emps, never()).insert(any(Employee.class));
        verify(roles, never()).insert(any(SysUserRole.class));
    }

    @Test
    void migratedFlagSetAfterSuccess() {
        UseUnit u = unit();
        setUp(List.of(u), null);
        svc.unifyPending();
        assertEquals(1, u.accountMigrated);
        verify(units).updateById(u);
    }

    @Test
    void newEmployeeGetsUnitDepartmentAndClientAdminPrimary() {
        setUp(List.of(unit()), null);
        svc.unifyPending();
        verify(emps).insert(org.mockito.ArgumentMatchers.<Employee>argThat(e ->
                "UNIT".equals(e.department) && "CLIENT_ADMIN".equals(e.role)
                        && Boolean.TRUE.equals(e.enabled) && "hash-unit".equals(e.passwordHash)));
    }

    @Test
    void roleRowAnchorsOrgFlatToUnit() {
        setUp(List.of(unit()), null);
        svc.unifyPending();
        verify(roles).insert(org.mockito.ArgumentMatchers.<SysUserRole>argThat(r ->
                "CLIENT_ADMIN".equals(r.roleCode) && "EMPLOYEE".equals(r.userType) && "u1".equals(r.orgId)));
    }
}