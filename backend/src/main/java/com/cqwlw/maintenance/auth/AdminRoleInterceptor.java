package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端角色门禁（docs/09 §三）：在 AuthInterceptor 之后执行（此时 role 已写入 attribute）。
 * 覆盖 /admin/**、/reg/**、/platform/**、/company、/use-units/**、/employees/**、/elevators/**。
 * 规则：写操作（POST/PUT/DELETE）需 ADMIN/SYS_ADMIN；读操作需 LEADER/ADMIN/SYS_ADMIN；
 * V2.15 起**无 SYS_ADMIN 专属端点**（docs/01 §10.2：SYS_ADMIN 及其专属功能裁剪——
 * 旧 /admin/op-logs 查询、PUT /admin/employees/{id}/password 已删除；账号启停 PUT /admin/employees/{id}/enabled 改按 ADMIN+ 门禁；
 * 角色本身保留供既有账号兼容，见 docs/09 §3.1"保留不启用"；审计留痕 AuditInterceptor 仍写 op_log，仅无查询界面）；
 * 例外：GET /elevators* 为小程序共用读接口，任何已登录角色放行；
 *       二期例外（docs/04 A.2/A.5）：发起延期（POST /admin/plans/{id}/delay）与
 *       转派（POST /admin/orders/{id}/transfer）为"班组长及以上"，LEADER 可写。
 */
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final java.util.regex.Pattern LEADER_WRITABLE =
            java.util.regex.Pattern.compile("^/admin/(plans/[^/]+/delay|orders/[^/]+/transfer)$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        // M12：用 Spring 实际匹配的路由模式（与 Security/路由同源），getRequestURI 是原始未解码路径，
        // 二者不同源在反向代理子路径部署时会错配；无 attribute 时回退（兼容单测/Filter 之前场景）
        Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = bestPattern != null ? String.valueOf(bestPattern) : request.getRequestURI();
        boolean write = !"GET".equals(request.getMethod());
        boolean allowed;
        if (!write && path.startsWith("/elevators")) {
            return true; // 小程序共用电梯读接口
        } else if (write && LEADER_WRITABLE.matcher(path).matches()) {
            allowed = AdminRoles.canRead(role); // 班组长及以上
        } else if (write) {
            allowed = AdminRoles.canWrite(role);
        } else {
            allowed = AdminRoles.canRead(role);
        }
        if (!allowed) {
            throw new BizException(403, write
                    ? "该操作需要维保部管理员权限（ADMIN/SYS_ADMIN）"
                    : "该账号无管理端权限，请使用小程序");
        }
        return true;
    }
}
