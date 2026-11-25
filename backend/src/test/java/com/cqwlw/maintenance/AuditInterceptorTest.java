package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuditInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.entity.OpLog;
import com.cqwlw.maintenance.mapper.OpLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditInterceptorTest {

    private OpLogMapper opLogMapper;
    private AuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        opLogMapper = mock(OpLogMapper.class);
        interceptor = new AuditInterceptor(opLogMapper);
    }

    private HttpServletRequest request(String method, String uri, String empId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn(empId);
        return req;
    }

    @Test
    void recordsWriteAsSuccess() {
        interceptor.afterCompletion(request("PUT", "/admin/alert-rules/al_1", "emp_admin"),
                mock(HttpServletResponse.class), new Object(), null);
        ArgumentCaptor<OpLog> captor = ArgumentCaptor.forClass(OpLog.class);
        verify(opLogMapper).insert(captor.capture());
        assertEquals("SUCCESS", captor.getValue().result);
        assertEquals("/admin/alert-rules/al_1", captor.getValue().path);
        assertEquals("emp_admin", captor.getValue().operatorId);
    }

    @Test
    void recordsFailedOnException() {
        interceptor.afterCompletion(request("POST", "/admin/alerts/generate", "emp_admin"),
                mock(HttpServletResponse.class), new Object(), new RuntimeException("x"));
        ArgumentCaptor<OpLog> captor = ArgumentCaptor.forClass(OpLog.class);
        verify(opLogMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().result);
    }

    @Test
    void skipsReadRequests() {
        interceptor.afterCompletion(request("GET", "/admin/alerts", "emp_admin"),
                mock(HttpServletResponse.class), new Object(), null);
        interceptor.afterCompletion(request("GET", "/admin/op-logs", "emp_admin"),
                mock(HttpServletResponse.class), new Object(), null);
        verify(opLogMapper, never()).insert(any(OpLog.class));
    }

    @Test
    void operatorNameLeftNullResolvedAtListTime() {
        interceptor.afterCompletion(request("POST", "/admin/plans/generate", "emp_admin"),
                mock(HttpServletResponse.class), new Object(), null);
        ArgumentCaptor<OpLog> captor = ArgumentCaptor.forClass(OpLog.class);
        verify(opLogMapper, times(1)).insert(captor.capture());
        assertEquals(null, captor.getValue().operatorName);
    }

    /** action 截断取前 64 字符：超长 URI 必须保住 /admin/ 路由前缀，丢前缀的审计无法定位模块 */
    @Test
    void actionTruncationKeepsUriPrefix() {
        String uri = "/admin/elevators/" + "x".repeat(80) + "/drawer";
        interceptor.afterCompletion(request("PUT", uri, "emp_admin"),
                mock(HttpServletResponse.class), new Object(), null);
        ArgumentCaptor<OpLog> captor = ArgumentCaptor.forClass(OpLog.class);
        verify(opLogMapper).insert(captor.capture());
        assertEquals(uri.substring(0, 64), captor.getValue().action);
    }
}
