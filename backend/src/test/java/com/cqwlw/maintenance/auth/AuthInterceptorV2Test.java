package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorV2Test {

    private final JwtService jwt = mock(JwtService.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("GET");
        req.setRequestURI(uri);
        req.addHeader("Authorization", "Bearer token");
        return req;
    }

    private Claims claims(String sub, String role, String client, String sid, Integer sv) {
        Claims c = mock(Claims.class);
        when(c.getSubject()).thenReturn(sub);
        when(c.get("role", String.class)).thenReturn(role);
        when(c.get("client", String.class)).thenReturn(client);
        when(c.get("sid", String.class)).thenReturn(sid);
        when(c.get("sv")).thenReturn(sv);
        when(c.get("ut", String.class)).thenReturn(null);
        return c;
    }

    private Employee employee(String id, String role, String sid, Integer sv) {
        Employee e = new Employee();
        e.id = id;
        e.role = role;
        e.sessionId = sid;
        e.scopeVersion = sv;
        return e;
    }

    @Test
    void scopeVersionMismatchRejects() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        Claims c = claims("emp_1", "WORKER", "mp", "s1", 3);
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", 5));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(request("/work-orders"), response, handler));
        assertEquals(401, e.getCode());
    }

    @Test
    void disabledEmployeeTokenRejected() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        Claims c = claims("emp_1", "WORKER", "mp", "s1", 3);
        Employee disabled = employee("emp_1", "WORKER", "s1", 3);
        disabled.enabled = false;
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById("emp_1")).thenReturn(disabled);

        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(request("/work-orders"), response, handler));

        assertEquals(401, e.getCode());
    }

    @Test
    void legacyTokenWithoutSvMatchesDefaultZero() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        Claims c = claims("emp_1", "WORKER", "mp", "s1", null);
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", null));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        assertEquals("WORKER", req.getAttribute(AuthInterceptor.ATTR_ROLE));
    }

    @Test
    void multiRoleSetPopulatedFromUserRoles() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        Claims c = claims("emp_1", "LEADER", "mp", "s1", 0);
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "LEADER", "s1", 0));
        SysUserRole r1 = new SysUserRole(); r1.roleCode = "MAINT_SUP";
        SysUserRole r2 = new SysUserRole(); r2.roleCode = "MAINT_TECH";
        when(userRoleMapper.selectList(any())).thenReturn(List.of(r1, r2));
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        assertEquals("LEADER", req.getAttribute(AuthInterceptor.ATTR_ROLE)); // 主角色旧码逐字不变
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(AuthInterceptor.ATTR_ROLES);
        assertEquals(Set.of("MAINT_SUP", "MAINT_TECH", "LEADER"), roles);
    }

    @Test
    void twoArgConstructorFallsBackToPrimaryRole() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper); // 2 参委托
        Claims c = claims("emp_1", "WORKER", "mp", "s1", 0);
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", 0));
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(AuthInterceptor.ATTR_ROLES);
        assertEquals(Set.of("MAINT_TECH"), roles); // 回退主角色规范码
    }


    /** 构造合法统一令牌（client=mp、sid/sv 匹配），sys_user_role 返回给定角色码（经 canonical 归一）。 */
    private AuthInterceptor interceptorFor(String sub, String primaryRole, String... roleCodes) {
        Claims c = claims(sub, primaryRole, "mp", "s1", 0); // 先建 mock 再 stub，避免嵌套 stubbing
        when(jwt.verify(anyString())).thenReturn(c);
        when(employeeMapper.selectById(sub)).thenReturn(employee(sub, primaryRole, "s1", 0));
        java.util.List<SysUserRole> rows = new java.util.ArrayList<>();
        for (String code : roleCodes) {
            SysUserRole r = new SysUserRole();
            r.roleCode = code;
            rows.add(r);
        }
        when(userRoleMapper.selectList(any())).thenReturn(rows);
        return new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
    }

    private HttpServletRequest request(String uri, String method) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod(method);
        req.setRequestURI(uri);
        req.addHeader("Authorization", "Bearer token");
        return req;
    }

    @Test
    void customerTokenRejectedOnInternalEndpoints() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN", "CLIENT_ADMIN");
        for (String uri : new String[]{"/users", "/org", "/stats", "/audit", "/invites",
                "/admin/dashboard", "/employees", "/home/summary"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> interceptor.preHandle(request(uri), response, handler), uri);
            assertEquals(403, ex.getCode());
            assertTrue(ex.getMessage().contains("客户"), uri);
        }
    }

    /** 客户令牌打员工作业端点（工单/移交/日历/消息）→ 403；多客户角色（ADMIN+SIGNER）同判 */
    @Test
    void customerTokenRejectedOnStaffWorkEndpoints() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN", "CLIENT_ADMIN", "CLIENT_SIGNER");
        for (String uri : new String[]{"/work-orders", "/handovers", "/maintenance-calendar", "/messages"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> interceptor.preHandle(request(uri), response, handler), uri);
            assertEquals(403, ex.getCode());
        }
    }

    /** 客户门户与自身账号端点放行：/unit/**、/me/menus、/auth/me、改密/绑微信（POST）、GET /elevators*（含 profile） */
    @Test
    void customerTokenAllowedOnPortalAndOwnEndpoints() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN", "CLIENT_ADMIN");
        for (String uri : new String[]{"/unit/records/pending", "/unit/elevators", "/me/menus",
                "/auth/me", "/elevators", "/elevators/e1/profile"}) {
            assertTrue(interceptor.preHandle(request(uri), response, handler), uri);
        }
        assertTrue(interceptor.preHandle(request("/auth/change-password", "POST"), response, handler));
        assertTrue(interceptor.preHandle(request("/auth/bind-wechat", "POST"), response, handler));
    }

    /** 电梯族仅 GET 读放行：客户令牌写操作（手动派单）→ 403 */
    @Test
    void customerTokenRejectedOnElevatorWrite() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN", "CLIENT_ADMIN");
        BizException ex = assertThrows(BizException.class, () -> interceptor.preHandle(
                request("/elevators/e1/dispatch", "POST"), response, handler));
        assertEquals(403, ex.getCode());
    }

    /** 内部员工放行逐条不变：MAINT_TECH 打 /work-orders 不受客户白名单影响 */
    @Test
    void employeeTokenUnaffectedByCustomerWhitelist() {
        AuthInterceptor interceptor = interceptorFor("emp_1", "WORKER", "MAINT_TECH");
        assertTrue(interceptor.preHandle(request("/work-orders"), response, handler));
    }

    /** 混合角色（含任一内部角色）走员工路径，不被误锁（Review Focus #1：全部 CUSTOMER 才锁） */
    @Test
    void mixedRolesNotLockedToCustomerWhitelist() {
        AuthInterceptor interceptor = interceptorFor("emp_1", "MAINT_MANAGER", "MAINT_MGR", "CLIENT_ADMIN");
        assertTrue(interceptor.preHandle(request("/users"), response, handler));
    }

    /** 旧客户账号无 sys_user_role 行：回退主角色 UNIT_ADMIN→CLIENT_ADMIN，仍判客户主体 → /users 403 */
    @Test
    void legacyUnitAdminFallbackStillCustomer() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN");
        BizException ex = assertThrows(BizException.class,
                () -> interceptor.preHandle(request("/users"), response, handler));
        assertEquals(403, ex.getCode());
    }

    @Test
    void internalPrimaryWithCustomerRowsTakesEmployeePath() {
        AuthInterceptor interceptor = interceptorFor("emp_h", "WORKER", "CLIENT_ADMIN");
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler), "混合角色不得被客户白名单误锁");
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(AuthInterceptor.ATTR_ROLES);
        assertEquals(Set.of("MAINT_TECH", "CLIENT_ADMIN"), roles, "并集口径：行 ∪ 主角色 canonical");
        assertTrue(interceptor.preHandle(request("/users"), response, handler));
    }

    @Test
    void clientWhitelistUsesSlashBoundary() {
        AuthInterceptor interceptor = interceptorFor("emp_c", "UNIT_ADMIN", "CLIENT_ADMIN");
        BizException ex = assertThrows(BizException.class, () -> interceptor.preHandle(
                request("/elevators-secret", "GET"), response, handler));
        assertEquals(403, ex.getCode(), "startsWith 无斜杠边界会误放行前缀形 URI");
        assertTrue(interceptor.preHandle(request("/files/upload", "POST"), response, handler),
                "客户报修照片上传放行（仅此一条 /files 写路径）");
        BizException sts = assertThrows(BizException.class, () -> interceptor.preHandle(
                request("/files/sts", "POST"), response, handler));
        assertEquals(403, sts.getCode(), "其余 /files 写侧仍不对客户开放");
        assertTrue(interceptor.preHandle(request("/unit/faults", "POST"), response, handler));
        assertTrue(interceptor.preHandle(request("/elevators", "GET"), response, handler));
        assertTrue(interceptor.preHandle(request("/elevators/e1/profile", "GET"), response, handler));
    }
}