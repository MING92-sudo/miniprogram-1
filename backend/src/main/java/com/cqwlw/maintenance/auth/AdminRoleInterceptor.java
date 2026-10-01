package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端角色门禁（docs/09 §三）：在 AuthInterceptor 之后执行（此时 role 已写入 attribute）。
 * 覆盖 /admin/**、/reg/**、/platform/**、/company、/use-units/**、/employees/**、/elevators/**。
 * 规则：写操作（POST/PUT/DELETE）需 ADMIN/SYS_ADMIN；读操作需 LEADER/ADMIN/SYS_ADMIN；
 * 例外：GET /elevators* 为小程序共用读接口，任何已登录角色放行。
 */
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        boolean write = !"GET".equals(request.getMethod());
        if (!write && request.getRequestURI().startsWith("/elevators")) {
            return true;
        }
        boolean allowed = write ? AdminRoles.canWrite(role) : AdminRoles.canRead(role);
        if (!allowed) {
            throw new BizException(403, write
                    ? "该操作需要维保部管理员权限（ADMIN/SYS_ADMIN）"
                    : "该账号无管理端权限，请使用小程序");
        }
        return true;
    }
}
