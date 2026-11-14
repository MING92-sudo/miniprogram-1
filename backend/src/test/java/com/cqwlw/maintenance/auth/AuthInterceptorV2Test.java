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

/** AuthInterceptor v2：sv 校验（null 安全）、ATTR_ROLES、门户 CLIENT_ADMIN 别名。 */
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
        assertEquals(Set.of("MAINT_SUP", "MAINT_TECH"), roles);
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
}