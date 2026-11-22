package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.controller.AdminArchiveController;
import com.cqwlw.maintenance.controller.PlatformController;
import com.cqwlw.maintenance.controller.RegAdminController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest request(String method, String uri, String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(uri);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    private HandlerMethod handler(Class<?> beanType, String methodName, Class<?>... parameterTypes) {
        try {
            return new HandlerMethod(mock(beanType), beanType.getMethod(methodName, parameterTypes));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void encodedUriStillMatchedByPattern() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/admin/plans/pl%201/delay");
        when(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/admin/plans/{id}/delay");
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("LEADER");
        assertThrows(BizException.class, () -> interceptor.preHandle(req, response, handler));
    }

    @Test
    void leaderCannotInitiateDelay() {
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("POST", "/admin/plans/pl_1/delay", "LEADER"), response, handler));
    }

    @Test
    void leaderCannotAssignOrGenerate() {
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("PUT", "/admin/plans/pl_1/assign", "LEADER"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("POST", "/admin/plans/generate", "LEADER"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("PUT", "/admin/plans/delays/pd_1/decide", "LEADER"), response, handler));
    }

    @Test
    void workerAlwaysRejected() {
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("POST", "/admin/plans/pl_1/delay", "WORKER"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("GET", "/admin/plans", "WORKER"), response, handler));
    }

    @Test
    void employeeEnabledIsAdminWritable() {
        assertTrue(interceptor.preHandle(
                request("PUT", "/admin/employees/emp_1/enabled", "ADMIN"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("PUT", "/admin/employees/emp_1/enabled", "LEADER"), response, handler));
    }

    @Test
    void opLogsEndpointRemovedFromGate() {
        assertTrue(interceptor.preHandle(
                request("GET", "/admin/op-logs", "ADMIN"), response, handler),
                "门禁不再对 /admin/op-logs 做 SYS_ADMIN 专属校验（端点已删除，请求将由框架返回 404）");
    }

    @Test
    void adminFullAccess() {
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/admin/plans/pl_1/assign", "ADMIN"), response, handler));
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/admin/plans/delays/pd_1/decide", "SYS_ADMIN"), response, handler));
    }

    @Test
    void sensitiveReadsRequireFineGrainedPermission() {
        Object[][] endpoints = {
                {"/employees", handler(AdminArchiveController.class, "employees"), PermissionMatrix.USER_VIEW},
                {"/platform/workers", handler(PlatformController.class, "workers", String.class),
                        PermissionMatrix.PLATFORM_WORKER_VIEW},
                {"/reg/sync-status", handler(RegAdminController.class, "syncStatus"), PermissionMatrix.REPORT_VIEW}
        };
        org.junit.jupiter.api.Assertions.assertAll(java.util.Arrays.stream(endpoints).map(endpoint -> () -> {
            String path = (String) endpoint[0];
            HandlerMethod handler = (HandlerMethod) endpoint[1];
            BizException ex = assertThrows(BizException.class, () -> interceptor.preHandle(
                    request("GET", path, "MAINT_BIZ"), response, handler), path);
            assertEquals(403, ex.getCode(), path);
            assertEquals("无权限: " + endpoint[2], ex.getMessage(), path);
        }));
    }

    @Test
    void workerCanEditOwnElevatorLocalInfo() {
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/elevators/{id}/project-name", "WORKER"), response, handler));
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/elevators/{id}/local", "WORKER"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("POST", "/elevators/{id}/dispatch", "WORKER"), response, handler));
    }
}
