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

/** 操作审计（docs/02 op_log）：管理写操作留痕（POST/PUT/DELETE），GET 不记录，异常记 FAILED */
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
}
