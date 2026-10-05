package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端门禁（docs/09 二期）：发起延期与转派为"班组长及以上"（LEADER 可写），
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
        // M12：拦截器改读 BEST_MATCHING_PATTERN_ATTRIBUTE；单测以 uri 充当路由模式
        when(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(uri);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    /** M12：路由模式与原始 URI 不同源时（如 %20 编码子路径），门禁按模式判定不误杀 */
    @Test
    void encodedUriStillMatchedByPattern() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/admin/plans/pl%201/delay");
        when(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/admin/plans/{id}/delay");
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("LEADER");
        assertDoesNotThrow(() -> interceptor.preHandle(req, response, handler));
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

    /** V2.15：SYS_ADMIN 专属门禁取消——账号启停改按写操作门禁（ADMIN+ 可用，LEADER/WORKER 拒绝） */
    @Test
    void employeeEnabledIsAdminWritable() {
        assertTrue(interceptor.preHandle(
                request("PUT", "/admin/employees/emp_1/enabled", "ADMIN"), response, handler));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("PUT", "/admin/employees/emp_1/enabled", "LEADER"), response, handler));
    }

    /** V2.15：审计查询端点已删除 → 命中框架 404，不再由门禁放行/拒绝（此处仅断言不再要求 SYS_ADMIN） */
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
}
