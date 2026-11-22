package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.HandlerInterceptor;

/** 电梯本地信息写端点在服务层校验归属。 */
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final java.util.regex.Pattern LEADER_WRITABLE =
            java.util.regex.Pattern.compile("^/admin/plans/[^/]+/delay$");

    /** 维保人员现场可写的电梯端点（归属校验在 ElevatorService，仅本人负责的电梯，越权 1403） */
    private static final java.util.regex.Pattern WORKER_WRITABLE_ELEVATOR =
            java.util.regex.Pattern.compile("^/elevators/[^/]+/(project-name|local)$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        // 无 attribute 时回退（兼容单测/Filter 之前场景）
        Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = bestPattern != null ? String.valueOf(bestPattern) : request.getRequestURI();
        boolean write = !"GET".equals(request.getMethod());
        String required = requiredPermission(handler);
        boolean allowed;
        if (!write && path.startsWith("/elevators")) {
            return true; // 小程序共用电梯读接口
        } else if (write && WORKER_WRITABLE_ELEVATOR.matcher(path).matches()) {
            return true; // 维保人员现场更正电梯本地信息，越权由 ElevatorService 归属校验兜底（1403）
        } else if (required != null) {
            // 旧 24 码走兼容 allows。
            if (PermissionMatrix.canonicalCapabilities().contains(required)) {
                if (write) {
                    MultiRoleGate.requireWrite(request, required);
                } else {
                    MultiRoleGate.requireRead(request, required);
                }
                return true;
            }
            allowed = PermissionMatrix.allows(role, required); // docs/14 §9.3：端点按能力码授权
        } else if (write && LEADER_WRITABLE.matcher(path).matches()) {
            allowed = PermissionMatrix.allows(role, PermissionMatrix.PLAN_WRITE); // 班组长及以上（本组范围由 DeptScopeService 收敛）
        } else if (write) {
            allowed = PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_WRITE);
        } else {
            allowed = PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_READ);
        }
        if (!allowed) {
            throw new BizException(403, write
                    ? "该操作需要维保部管理员权限（ADMIN/SYS_ADMIN）"
                    : "该账号无管理端权限，请使用小程序");
        }
        return true;
    }

    /** 端点声明的能力码：方法级 {@link RequirePerm} 优先，其次类级；未标注返回 null（走旧两档门禁） */
    private static String requiredPermission(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequirePerm onMethod = method.getMethodAnnotation(RequirePerm.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequirePerm onType = method.getBeanType().getAnnotation(RequirePerm.class);
        return onType == null ? null : onType.value();
    }
}
