package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P3 客户令牌（ut=2）拦截器分支（docs/14 §4.6 路由白名单、§8 单会话/状态门禁）：
 * 账号状态、单会话 sid、scopeVersion、路径白名单四条门禁 fail-closed。
 */
class ClientTokenInterceptorTest {

    private JwtService jwt;
    private ClientAccountMapper clientMapper;
    private AuthInterceptor interceptor;
    private HttpServletResponse response = mock(HttpServletResponse.class);
    private Object handler = new Object();

    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        clientMapper = mock(ClientAccountMapper.class);
        interceptor = new AuthInterceptor(jwt, mock(EmployeeMapper.class), null);
        interceptor.setClientAccountMapper(clientMapper);
    }

    private HttpServletRequest req(String uri, String method) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI(uri);
        r.setMethod(method);
        r.addHeader("Authorization", "Bearer tok");
        return r;
    }

    private Claims clientClaims(String sub, String sid, int sv) {
        Claims c = mock(Claims.class);
        when(jwt.verify(anyString())).thenReturn(c);
        when(c.get("ut", String.class)).thenReturn("2");
        when(c.getSubject()).thenReturn(sub);
        when(c.get("sid", String.class)).thenReturn(sid);
        when(c.get("sv")).thenReturn(sv);
        when(c.get("role", String.class)).thenReturn("CLIENT_ADMIN");
        when(c.get("openid")).thenReturn(null);
        return c;
    }

    private ClientAccount account(String status, String sid, int sv) {
        ClientAccount c = new ClientAccount();
        c.id = "ca_1";
        c.useUnitId = "uu_1";
        c.roleCode = "CLIENT_ADMIN";
        c.status = status;
        c.enabled = ClientAccount.STATUS_DISABLED.equals(status) ? 0 : 1;
        c.sessionId = sid;
        c.scopeVersion = sv;
        return c;
    }

    @Test
    void validClientTokenPassesOnClientPath() {
        clientClaims("ca_1", "sess_1", 2);
        when(clientMapper.selectById("ca_1")).thenReturn(account("ACTIVE", "sess_1", 2));
        HttpServletRequest r = req("/client/records/pending", "GET");
        assertTrue(interceptor.preHandle(r, response, handler));
        assertEquals("CUSTOMER", r.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE));
        assertEquals("uu_1", r.getAttribute(AuthInterceptor.ATTR_UNIT_ID));
        assertEquals("ca_1", r.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @Test
    void disabledAccountRejected() {
        clientClaims("ca_1", "sess_1", 2);
        when(clientMapper.selectById("ca_1")).thenReturn(account("DISABLED", "sess_1", 2));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("/client/records/pending", "GET"), response, handler));
        assertEquals(401, e.getCode());
    }

    @Test
    void sessionMismatchRejected() {
        clientClaims("ca_1", "sess_old", 2);
        when(clientMapper.selectById("ca_1")).thenReturn(account("ACTIVE", "sess_new", 2));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("/client/records/pending", "GET"), response, handler));
        assertEquals(401, e.getCode());
    }

    @Test
    void scopeVersionMismatchRejected() {
        clientClaims("ca_1", "sess_1", 1);
        when(clientMapper.selectById("ca_1")).thenReturn(account("ACTIVE", "sess_1", 2));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("/client/records/pending", "GET"), response, handler));
        assertEquals(401, e.getCode());
    }

    @Test
    void internalAdminPathForbiddenForClient() {
        clientClaims("ca_1", "sess_1", 2);
        when(clientMapper.selectById("ca_1")).thenReturn(account("ACTIVE", "sess_1", 2));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("/use-units", "GET"), response, handler));
        assertEquals(403, e.getCode());
    }

    @Test
    void selectionTokenOnlyAllowedOnSelectUnit() {
        Claims c = mock(Claims.class);
        when(jwt.verify(anyString())).thenReturn(c);
        when(c.get("ut", String.class)).thenReturn(null);
        when(c.get("sel", String.class)).thenReturn("1");
        assertTrue(interceptor.preHandle(req("/auth/client/select-unit", "POST"), response, handler));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(req("/client/records/pending", "GET"), response, handler));
        assertEquals(403, e.getCode());
    }
}
