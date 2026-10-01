package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端角色门禁：在 AuthInterceptor 之后执行（此时 role 已写入 attribute）。
 * 覆盖 /admin/**、/reg/**、/platform/**、/company、/use-units/**、/employees/**、/elevators/**。
 * 规则：写操作（POST/PUT/DELETE）需 ADMIN/SYS_ADMIN；读操作需 LEADER/ADMIN/SYS_ADMIN；
 * 例外：GET /elevators* 为小程序共用读接口，任何已登录角色放行；
 *       例外：发起延期（POST /admin/plans/{id}/delay）与
 *       转派（POST /admin/orders/{id}/transfer）为"班组长及以上"，LEADER 可写。
 */
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final java.util.regex.Pattern LEADER_WRITABLE =
            java.util.regex.Pattern.compile("^/admin/(plans/[^/]+/delay|orders/[^/]+/transfer)$");

    /** 系统管理员专属（用户权限/接口配置/日志审计） */
    private static final java.util.regex.Pattern SYS_ONLY =
            java.util.regex.Pattern.compile("^/admin/(op-logs|employees/[^/]+/(enabled|password))$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        String path = request.getRequestURI();
        boolean write = !"GET".equals(request.getMethod());
        boolean allowed;
        if (SYS_ONLY.matcher(path).matches()) {
            if (!AdminRoles.SYS_ADMIN.equals(role)) {
                throw new BizException(403, "该操作需要系统管理员权限（SYS_ADMIN）");
            }
            return true;
        }
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
