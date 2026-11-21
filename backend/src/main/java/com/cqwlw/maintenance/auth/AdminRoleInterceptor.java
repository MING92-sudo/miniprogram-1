package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端角色门禁（docs/09 §三，权限口径 docs/14 §6）：在 AuthInterceptor 之后执行（此时 role 已写入 attribute）。
 * 覆盖 /admin/**、/reg/**、/platform/**、/company、/use-units/**、/employees/**、/elevators/**。
 * 规则：判定走 {@link PermissionMatrix}——读操作需 console:read，写操作需 console:write；
 * 端点标注 {@link RequirePerm} 时按该能力码判定（P3 起逐模块标注，本期无端点使用）；
 * V2.15 起**无 SYS_ADMIN 专属端点**（docs/01 §10.2：SYS_ADMIN 及其专属功能裁剪——
 * 旧 /admin/op-logs 查询、PUT /admin/employees/{id}/password 已删除；账号启停 PUT /admin/employees/{id}/enabled 改按 ADMIN+ 门禁；
 * 角色本身保留供既有账号兼容，见 docs/09 §3.1"保留不启用"；审计留痕 AuditInterceptor 仍写 op_log，仅无查询界面）；
 * 例外：GET /elevators* 为小程序共用读接口，任何已登录角色放行；
 *       PUT /elevators/{id}/project-name、PUT /elevators/{id}/local 为维保人员现场更正电梯本地信息，
 *       任何已登录角色放行（越权由 ElevatorService 归属校验兜底，1403）；POST /elevators/{id}/dispatch 手动派单仍需管理员；
 *       二期例外（docs/04 A.5）：发起延期（POST /admin/plans/{id}/delay）为"班组长及以上"，
 *       LEADER 可写（本组范围由 DeptScopeService 收敛）。
 */
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
        // M12：用 Spring 实际匹配的路由模式（与 Security/路由同源），getRequestURI 是原始未解码路径，
        // 二者不同源在反向代理子路径部署时会错配；无 attribute 时回退（兼容单测/Filter 之前场景）
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
            // C-11：端点按能力码授权。新码（86 三态）走多角色门控；旧 24 码走兼容 allows。
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
