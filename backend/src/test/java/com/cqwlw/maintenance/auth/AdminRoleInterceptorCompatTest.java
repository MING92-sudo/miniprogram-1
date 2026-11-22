package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.WebConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 门禁改造兼容性：{@link AdminRoleInterceptor} 改走 PermissionMatrix 后，现有 5 个角色在 (HTTP 方法, 路由) 组合上的放行结果必须与改造前逐条一致。 */
class AdminRoleInterceptorCompatTest {

    /** 改造前：读=LEADER/ADMIN/SYS_ADMIN，写=ADMIN/SYS_ADMIN */
    private static final Set<String> LEGACY_READ = Set.of("LEADER", "ADMIN", "SYS_ADMIN");
    private static final Set<String> LEGACY_WRITE = Set.of("ADMIN", "SYS_ADMIN");
    private static final Pattern LEGACY_LEADER_WRITABLE =
            Pattern.compile("^/admin/plans/[^/]+/delay$");
    private static final Pattern LEGACY_WORKER_WRITABLE_ELEVATOR =
            Pattern.compile("^/elevators/[^/]+/(project-name|local)$");

    private static final List<String> EXISTING_ROLES =
            List.of("ADMIN", "SYS_ADMIN", "LEADER", "WORKER", "UNIT_ADMIN");

    /** 覆盖门禁注册的 7 个前缀 + 移交新路由（门禁未注册，保持现状） */
    private static final List<String[]> ROUTES = List.<String[]>of(
            new String[]{"GET", "/admin/dashboard"},
            new String[]{"PUT", "/admin/plans/{id}/assign"},
            new String[]{"POST", "/admin/plans/{id}/delay"},
            new String[]{"GET", "/reg/upload-logs"},
            new String[]{"GET", "/reg/sync-status"},
            new String[]{"GET", "/platform/workers"},
            new String[]{"POST", "/platform/sync"},
            new String[]{"GET", "/company"},
            new String[]{"GET", "/employees"},
            new String[]{"POST", "/employees/{id}/reset-password"},
            new String[]{"GET", "/elevators"},
            new String[]{"PUT", "/elevators/{id}/project-name"},
            new String[]{"PUT", "/elevators/{id}/local"},
            new String[]{"POST", "/elevators/{id}/dispatch"},
            new String[]{"GET", "/handovers"},
            new String[]{"POST", "/handovers/{id}/confirm"});

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest request(String method, String path, String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(path);
        when(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(path);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    /** 改造前的 preHandle 判定（原样内联） */
    private static boolean legacyAllows(String method, String path, String role) {
        boolean write = !"GET".equals(method);
        if (!write && path.startsWith("/elevators")) {
            return true;
        }
        if (write && LEGACY_WORKER_WRITABLE_ELEVATOR.matcher(path).matches()) {
            return true;
        }
        if (write && LEGACY_LEADER_WRITABLE.matcher(path).matches()) {
            return LEGACY_READ.contains(role);
        }
        return write ? LEGACY_WRITE.contains(role) : LEGACY_READ.contains(role);
    }

    @Test
    void existingRolesKeepLegacyDecisionsOnEveryRoute() {
        for (String[] route : ROUTES) {
            for (String role : EXISTING_ROLES) {
                boolean expected = legacyAllows(route[0], route[1], role);
                HttpServletRequest req = request(route[0], route[1], role);
                String label = route[0] + " " + route[1] + " × " + role;
                if (expected) {
                    assertDoesNotThrow(() -> interceptor.preHandle(req, response, handler), label);
                } else {
                    BizException e = assertThrows(BizException.class,
                            () -> interceptor.preHandle(req, response, handler), label);
                    assertTrue(e.getCode() == 403, label + " 期望 403");
                }
            }
        }
    }

    /** 移交新路由不在旧门禁注册列表内，由控制器按细粒度能力码校验。 */
    @Test
    void handoverRoutesAreStillOutsideTheGate() {
        List<String> patterns = registeredPatterns();
        assertFalse(patterns.stream().anyMatch(p -> p.startsWith("/handovers")),
                "移交新路由不应在本期被 AdminRoleInterceptor 拦截: " + patterns);
        assertTrue(patterns.contains("/employees/**"), "门禁注册列表异常: " + patterns);
        assertTrue(patterns.contains("/elevators/**"), "门禁注册列表异常: " + patterns);
    }

    @Test
    void requirePermAnnotationOverridesTierRules() throws Exception {
        Method method = PermAnnotated.class.getMethod("handler");
        HandlerMethod annotated = new HandlerMethod(new PermAnnotated(), method);

        // GET /admin/dashboard 对 LEADER 是"读"放行；标注 region:write 后只有平台层可过
        assertDoesNotThrow(() -> interceptor.preHandle(
                request("GET", "/admin/dashboard", "ADMIN"), response, annotated));
        assertThrows(BizException.class, () -> interceptor.preHandle(
                request("GET", "/admin/dashboard", "LEADER"), response, annotated));
    }

    private List<String> registeredPatterns() {
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class, RETURNS_SELF);
        when(registry.addInterceptor(any())).thenReturn(registration);
        new WebConfig(mock(AuthInterceptor.class), interceptor, mock(AuditInterceptor.class),
                mock(LegacyModuleInterceptor.class))
                .addInterceptors(registry);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(registration, atLeastOnce()).addPathPatterns(captor.capture());
        List<String> patterns = new ArrayList<>();
        for (String[] captured : captor.getAllValues()) {
            patterns.addAll(List.of(captured));
        }
        return patterns;
    }

    /** 仅用于验证注解解析，不进 Spring 容器 */
    static class PermAnnotated {
        @RequirePerm(PermissionMatrix.REGION_WRITE)
        public void handler() {
        }
    }
}
