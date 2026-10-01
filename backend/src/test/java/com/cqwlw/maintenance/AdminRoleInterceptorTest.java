package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端门禁：发起延期与转派为"班组长及以上"（LEADER 可写），
 * 其余写操作仍需 ADMIN/SYS_ADMIN。
 */
class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest request(String method, String uri, String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getAttribute(any())).thenReturn(role);
        return req;
    }

    @Test
    void leaderCanInitiateDelayAndTransfer() {
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("POST", "/admin/plans/pl_1/delay", "LEADER"), response, handler));
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("POST", "/admin/orders/wo_1/transfer", "LEADER"), response, handler));
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
    void adminFullAccess() {
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/admin/plans/pl_1/assign", "ADMIN"), response, handler));
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("PUT", "/admin/plans/delays/pd_1/decide", "SYS_ADMIN"), response, handler));
    }
}
