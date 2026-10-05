package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1：员工被删除/停用时必须 401（前端清登录态），不得 NPE 500；
 * M2：角色以数据库为准——token claims 中的 role 在 DB 降权后不生效。
 */
class AuthInterceptorTest {

    private static final String EMP = "emp_1";

    private final JwtService jwtService = mock(JwtService.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService, employeeMapper);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Claims claims = mock(Claims.class);

    private Employee user(String role, String sessionId, String sessionAdmin) {
        Employee e = new Employee();
        e.id = EMP;
        e.role = role;
        e.sessionId = sessionId;
        e.sessionAdmin = sessionAdmin;
        return e;
    }

    @BeforeEach
    void setUp() {
        when(request.getHeader("Authorization")).thenReturn("Bearer tok");
        when(jwtService.verify("tok")).thenReturn(claims);
        when(claims.getSubject()).thenReturn(EMP);
        when(claims.get("client", String.class)).thenReturn("mp");
        when(claims.get("sid", String.class)).thenReturn("sid_1");
        when(claims.get("role")).thenReturn("SYS_ADMIN");
    }

    /** M1：user == null 先判空 → 401（旧实现先解引用 user.sessionAdmin → NPE 500） */
    @Test
    void deletedEmployeeReturns401Not500() {
        when(employeeMapper.selectById(EMP)).thenReturn(null);
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object()));
        assertEquals(401, e.getCode());
    }

    /** M2：claims.role=SYS_ADMIN 但 DB 已降权 WORKER → 实际角色取库值 */
    @Test
    void roleComesFromDatabaseNotClaims() {
        when(employeeMapper.selectById(EMP)).thenReturn(user("WORKER", "sid_1", "sid_1"));
        assertTrue(interceptor.preHandle(request, response, new Object()));
        ArgumentCaptor<Object> role = ArgumentCaptor.forClass(Object.class);
        verify(request).setAttribute(org.mockito.ArgumentMatchers.eq(AuthInterceptor.ATTR_ROLE), role.capture());
        assertEquals("WORKER", role.getValue());
    }

    @Test
    void sessionMismatchReturns401() {
        when(employeeMapper.selectById(EMP)).thenReturn(user("WORKER", "sid_other", "sid_other"));
        assertThrows(BizException.class, () -> interceptor.preHandle(request, response, new Object()));
    }
}
