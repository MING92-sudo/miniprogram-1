package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 2026-11-15 裁决：使用单位账号须先建单位档案并在其下开通（org_id=use_unit.id 锚点）。 */
class UserControllerUnitAnchorTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);

    private UserController controller() {
        return new UserController(employeeMapper, userRoleMapper, useUnitMapper);
    }

    private HttpServletRequest sysAdmin() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(Set.of("SYS_ADMIN"));
        return req;
    }

    @Test
    void clientAccountWithoutUnitArchiveRejected() {
        BizException ex = assertThrows(BizException.class, () -> controller().create(
                Map.of("name", "王芳", "phone", "13800000021", "roles", List.of("CLIENT_ADMIN")), sysAdmin()));
        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).insert(any(com.cqwlw.maintenance.entity.Employee.class));
    }

    @Test
    void clientAccountWithUnknownUnitRejected() {
        when(useUnitMapper.selectById("uu_x")).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> controller().create(
                Map.of("name", "王芳", "phone", "13800000021", "roles", List.of("CLIENT_ADMIN"),
                        "useUnitId", "uu_x"), sysAdmin()));
        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).insert(any(com.cqwlw.maintenance.entity.Employee.class));
    }

    @Test
    void clientAccountAnchoredToUnit() {
        when(useUnitMapper.selectById("uu_1")).thenReturn(new UseUnit());
        controller().create(Map.of("name", "王芳", "phone", "13800000021",
                "roles", List.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "useUnitId", "uu_1"), sysAdmin());
        ArgumentCaptor<SysUserRole> cap = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        for (SysUserRole ur : cap.getAllValues()) {
            assertEquals("uu_1", ur.orgId);
        }
    }

    @Test
    void nonUnitRoleNeedsNoAnchor() {
        controller().create(Map.of("name", "张伟", "phone", "13800000022",
                "roles", List.of("MAINT_TECH")), sysAdmin());
        ArgumentCaptor<SysUserRole> cap = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(cap.capture());
        assertNull(cap.getValue().orgId);
    }
}