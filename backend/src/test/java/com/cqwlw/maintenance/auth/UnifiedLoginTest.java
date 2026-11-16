package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.UnitAccountService;
import com.cqwlw.maintenance.service.WxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统一登录页（用户需求）：只保留一个登录界面，后端按账号类型分流——
 * 员工账号走原 JWT 会话；使用单位账号返回 portal=true，前端据此跳门户首页。
 */
class UnifiedLoginTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final UnitAccountService unitAccountService = mock(UnitAccountService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final UserRoleService userRoleService = mock(UserRoleService.class);

    private AuthController controller() {
        return new AuthController(employeeMapper, jwtService, mock(WxAuthService.class),
                mock(MenuService.class), unitAccountService, userRoleService);
    }

    /** 员工目录查不到 → 自动按使用单位账号再试，返回 portal=true */
    @Test
    void fallsBackToUnitAccount() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(unitAccountService.login("U0000001", "Passw0rd!23")).thenReturn(Map.of(
                "token", "unit-token", "useUnitId", "uu_1", "unitName", "重庆渝达物业",
                "role", "UNIT_ADMIN", "wechatBound", false));

        Map<String, Object> data = controller().login(Map.of("phone", "U0000001", "password", "Passw0rd!23")).getData();
        assertEquals(true, data.get("portal"));
        assertEquals("UNIT_ADMIN", data.get("role"));
        assertEquals("uu_1", data.get("useUnitId"));
        assertTrue(String.valueOf(data.get("userInfo")).contains("重庆渝达物业"));
    }

    /** 员工账号仍走原流程：portal 不出现（不能把员工当门户账号） */
    @Test
    void employeeLoginUnchanged() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        Employee e = new Employee();
        e.id = "emp_1";
        e.name = "张伟";
        e.role = "WORKER";
        e.account = "13800000001";
        e.passwordHash = enc.encode("Passw0rd!23");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any())).thenReturn(java.util.Set.of("MAINT_TECH"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("emp-token");

        Map<String, Object> data = controller().login(Map.of("phone", "13800000001", "password", "Passw0rd!23")).getData();
        assertEquals("WORKER", data.get("role"));
        assertEquals("emp-token", data.get("token"));
        assertEquals(null, data.get("portal"));
        // M1 扩展字段（旧字段兼容不变）
        assertEquals(java.util.List.of("MAINT_TECH"), data.get("roles"));
        assertEquals("MAINT_TECH", data.get("activeWorkspace"));
        assertEquals("/pages/maint/home/index", data.get("home"));
        assertTrue(data.get("perms") instanceof java.util.List);
        assertTrue(String.valueOf(data.get("scope")).contains("维保"));
        // P1 T8-A2：员工主体 principalType=EMPLOYEE（与 mock loginPayload 口径一致）
        assertEquals("EMPLOYEE", data.get("principalType"));
    }

    /** M1：CLIENT 账号登录路由使用单位工作台，无 portal 标记 */
    @Test
    void clientAccountLoginRoutesClientWorkspace() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        Employee e = new Employee();
        e.id = "emp_9";
        e.name = "张伟";
        e.role = "CLIENT_ADMIN";
        e.department = "UNIT";
        e.account = "13800000009";
        e.passwordHash = enc.encode("Passw0rd!23");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any()))
                .thenReturn(java.util.Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("client-token");

        Map<String, Object> data = controller().login(Map.of("phone", "13800000009", "password", "Passw0rd!23")).getData();
        assertEquals("CLIENT_ADMIN", data.get("activeWorkspace"));
        assertEquals("/pages/client/home/index", data.get("home"));
        assertEquals(null, data.get("portal"));
        assertTrue(((java.util.List<?>) data.get("roles")).containsAll(
                java.util.List.of("CLIENT_ADMIN", "CLIENT_SIGNER")));
        // P1 T8-A2：生效角色集全为客户角色 → principalType=CUSTOMER（小程序据此收敛客户入口）
        assertEquals("CUSTOMER", data.get("principalType"));
    }

    /** P1 T9：混合角色登录（内部 + 客户角色同时生效）→ principalType=EMPLOYEE，钉死 isCustomerPrincipal「全客户才收敛」口径 */
    @Test
    void mixedRoleLoginStaysEmployeePrincipal() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        Employee e = new Employee();
        e.id = "emp_m";
        e.name = "李娜";
        e.role = "MAINT_TECH";
        e.account = "13800000099";
        e.passwordHash = enc.encode("Passw0rd!23");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any()))
                .thenReturn(java.util.Set.of("MAINT_TECH", "CLIENT_SIGNER"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("mixed-token");

        Map<String, Object> data = controller().login(Map.of("phone", "13800000099", "password", "Passw0rd!23")).getData();
        assertEquals("EMPLOYEE", data.get("principalType"));
        assertTrue(((java.util.List<?>) data.get("roles")).containsAll(
                java.util.List.of("MAINT_TECH", "CLIENT_SIGNER")));
        assertEquals(null, data.get("portal"));
        assertEquals("mixed-token", data.get("token"));
    }

    /** M1：UserRoleService 并集含别名归一后的旧主角色 */
    @Test
    void canonicalRolesUnionIncludesAliasedLegacyPrimary() {
        com.cqwlw.maintenance.mapper.SysUserRoleMapper mapper =
                mock(com.cqwlw.maintenance.mapper.SysUserRoleMapper.class);
        com.cqwlw.maintenance.entity.SysUserRole row = new com.cqwlw.maintenance.entity.SysUserRole();
        row.userId = "e9";
        row.roleCode = "CLIENT_SIGNER";
        row.userType = "EMPLOYEE";
        when(mapper.selectList(any())).thenReturn(java.util.List.of(row));
        UserRoleService svc = new UserRoleService(mapper);

        java.util.Set<String> roles = svc.canonicalRolesOf("e9", "UNIT_ADMIN");

        assertEquals(java.util.Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), roles);
    }

    /** 管理端登录不接受使用单位账号（client=admin） */
    @Test
    void unitAccountCannotUseAdminConsole() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "U0000001", "password", "x", "client", "admin")));
        assertEquals(403, ex.getCode());
    }
}
