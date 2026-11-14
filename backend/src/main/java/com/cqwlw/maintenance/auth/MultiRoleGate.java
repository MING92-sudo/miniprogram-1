package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * M3 多角色三态门控：读 ATTR_ROLES（M1 多角色集），任一角色对能力码达 Y（写）或 Y/C（读）即放行。
 * 新码不走 AdminRoleInterceptor 旧两档（其 allows 仅认 24 旧码），M3 新端点用本门控显式判定。
 * 后端唯一安全边界；越权 403 且无副作用（AGENTS §3.1）。
 */
public final class MultiRoleGate {

    private MultiRoleGate() {
    }

    @SuppressWarnings("unchecked")
    public static Set<String> roles(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_ROLES);
        if (attr instanceof Set<?> set && !set.isEmpty()) {
            return (Set<String>) set;
        }
        Object single = request.getAttribute(AuthInterceptor.ATTR_ROLE);
        return single == null ? Set.of() : Set.of(String.valueOf(single));
    }

    public static void requireRead(HttpServletRequest request, String code) {
        require(request, code, false);
    }

    public static void requireWrite(HttpServletRequest request, String code) {
        require(request, code, true);
    }

    private static void require(HttpServletRequest request, String code, boolean write) {
        for (String raw : roles(request)) {
            String role = RoleCatalog.canonical(raw);
            if (role == null) {
                continue;
            }
            PermissionMatrix.TriState s = PermissionMatrix.state(role, code);
            if (write ? s == PermissionMatrix.TriState.Y
                    : (s == PermissionMatrix.TriState.Y || s == PermissionMatrix.TriState.C)) {
                return;
            }
        }
        throw new BizException(403, "无权限: " + code);
    }
}