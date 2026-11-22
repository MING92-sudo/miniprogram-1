package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerAuthorizationTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final UserController controller = new UserController(employeeMapper, userRoleMapper);

    @Test
    void departmentManagerCannotCreatePlatformAdmin() {
        BizException ex = assertThrows(BizException.class, () -> controller.create(
                Map.of("name", "越权账号", "phone", "13800000031", "roles", List.of("PLATFORM_ADMIN")),
                request("emp_mgr", "MAINT_MGR")));

        assertEquals(1403, ex.getCode());
        verify(employeeMapper, never()).insert(any(Employee.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void supervisorCannotCreateHigherOrCrossDepartmentRole() {
        for (String role : List.of("MAINT_MGR", "INST_PM")) {
            BizException ex = assertThrows(BizException.class, () -> controller.create(
                    Map.of("name", "越权账号", "phone", "13800000031", "roles", List.of(role)),
                    request("emp_sup", "MAINT_SUP")));
            assertEquals(1403, ex.getCode(), role);
        }
        verify(employeeMapper, never()).insert(any(Employee.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void managerCannotDisablePlatformAdminOrCrossDepartmentAccount() {
        when(employeeMapper.selectById("emp_platform")).thenReturn(employee("emp_platform", "PLATFORM_ADMIN", "PLATFORM"));
        when(employeeMapper.selectById("emp_install")).thenReturn(employee("emp_install", "INSTALLER", "INSTALL"));

        for (String id : List.of("emp_platform", "emp_install")) {
            BizException ex = assertThrows(BizException.class,
                    () -> controller.disable(id, request("emp_mgr", "MAINT_MGR")));
            assertEquals(1403, ex.getCode(), id);
        }
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).bumpScopeVersion(anyString());
    }

    @Test
    void cannotDisableSelf() {
        when(employeeMapper.selectById("emp_mgr")).thenReturn(employee("emp_mgr", "MAINT_MGR", "MAINT"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.disable("emp_mgr", request("emp_mgr", "MAINT_MGR")));

        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).bumpScopeVersion(anyString());
    }

    private HttpServletRequest request(String employeeId, String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn(employeeId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(Set.of(role));
        return req;
    }

    private static Employee employee(String id, String role, String department) {
        Employee employee = new Employee();
        employee.id = id;
        employee.role = role;
        employee.department = department;
        employee.enabled = true;
        employee.scopeVersion = 3;
        return employee;
    }
}
