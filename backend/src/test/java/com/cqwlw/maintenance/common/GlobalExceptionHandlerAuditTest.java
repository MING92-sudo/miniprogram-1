package com.cqwlw.maintenance.common;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 越权拒绝路径写 BLOCKED 审计；非越权业务码不写。 */
class GlobalExceptionHandlerAuditTest {

    private final AuditLogService audit = mock(AuditLogService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/admin/employees");
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn("emp_1");
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("WORKER");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    @Test
    void forbiddenWritesBlockedAudit() {
        ApiResponse<Object> resp = handler.handleBiz(new BizException(403, "无权限"), request());
        assertEquals(403, resp.getCode());
        ArgumentCaptor<SysAuditLog> cap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(audit).append(cap.capture());
        assertEquals("BLOCKED", cap.getValue().result);
        assertEquals("emp_1", cap.getValue().actorId);
    }

    @Test
    void dataScopeDeniedWritesBlockedAudit() {
        handler.handleBiz(new BizException(1403, "越权"), request());
        verify(audit).append(org.mockito.ArgumentMatchers.any(SysAuditLog.class));
    }

    @Test
    void validationErrorDoesNotWriteAudit() {
        handler.handleBiz(new BizException(422, "参数错误"), request());
        verify(audit, never()).append(org.mockito.ArgumentMatchers.any(SysAuditLog.class));
    }
}