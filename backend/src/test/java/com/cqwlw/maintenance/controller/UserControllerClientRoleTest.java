package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 客户主体归属：使用单位账号只存 client_account，人员目录（/users）拒绝一切客户角色，避免同一人两套登录身份（停用一处另一处仍可登录）。 */
class UserControllerClientRoleTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);

    private UserController controller() {
        return new UserController(employeeMapper, userRoleMapper);
    }

    private HttpServletRequest sysAdmin() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(Set.of("SYS_ADMIN"));
        return req;
    }

    @Test
    void clientAdminRoleRejectedInEmployeeDirectory() {
        BizException ex = assertThrows(BizException.class, () -> controller().create(
                Map.of("name", "王芳", "phone", "13800000021", "roles", List.of("CLIENT_ADMIN")), sysAdmin()));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("使用单位档案"), "报错须引导到客户账号开通入口：" + ex.getMessage());
        verify(employeeMapper, never()).insert(any(Employee.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void clientSignerRoleRejectedEvenWithUnitId() {
        BizException ex = assertThrows(BizException.class, () -> controller().create(
                Map.of("name", "签字人", "phone", "13800000022", "roles", List.of("CLIENT_SIGNER"),
                        "useUnitId", "uu_1"), sysAdmin()));
        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).insert(any(Employee.class));
    }

    @Test
    void assignRolesRejectsClientRole() {
        Employee e = new Employee();
        e.id = "emp_1";
        when(employeeMapper.selectById("emp_1")).thenReturn(e);
        BizException ex = assertThrows(BizException.class, () -> controller().assignRoles("emp_1",
                Map.of("roles", List.of("CLIENT_ADMIN"), "useUnitId", "uu_1"), sysAdmin()));
        assertEquals(422, ex.getCode());
        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void internalRoleNeedsNoUnitAnchor() {
        controller().create(Map.of("name", "张伟", "phone", "13800000022",
                "roles", List.of("MAINT_TECH")), sysAdmin());
        ArgumentCaptor<SysUserRole> cap = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(cap.capture());
        assertEquals("MAINT_TECH", cap.getValue().roleCode);
        assertNull(cap.getValue().orgId, "内部角色锚点由组织节点决定，不由 use_unit 决定");
    }
}
