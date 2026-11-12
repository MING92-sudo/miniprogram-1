package com.cqwlw.maintenance.auth;

import java.util.Set;

/**
 * 管理端角色口径（docs/14 §3/§6，语义由 {@link RoleCatalog} / {@link PermissionMatrix} 承载）：
 * READ  = 可登录管理端的 8 个角色（console:read），LEADER 只读；
 * WRITE = ADMIN/SYS_ADMIN（console:write，旧两档门禁；部门内细粒度写权限由能力码表达，P3 起端点标注）；
 * WORKER/UNIT_ADMIN 不使用管理端（小程序端角色不受影响）。
 * 本类仅为既有调用点保留常量与方法，新代码直接用 RoleCatalog / PermissionMatrix。
 */
public final class AdminRoles {

    public static final String WORKER = RoleCatalog.WORKER;
    public static final String LEADER = RoleCatalog.LEADER;
    public static final String UNIT_ADMIN = RoleCatalog.UNIT_ADMIN;
    public static final String ADMIN = RoleCatalog.ADMIN;
    public static final String SYS_ADMIN = RoleCatalog.SYS_ADMIN;

    private AdminRoles() {
    }

    /** 档案维护/手动重报/触发同步等写操作 */
    public static boolean canWrite(String role) {
        return PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_WRITE);
    }

    /** 管理端读操作 */
    public static boolean canRead(String role) {
        return PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_READ);
    }

    /** 登录接口 client=admin 时允许签发管理端会话的角色 */
    public static boolean canUseAdminConsole(String role) {
        return RoleCatalog.canUseAdminConsole(role);
    }

    /** 建档允许分配的角色（docs/14 §3）：除使用单位外的全部角色，规则见 RoleCatalog.assignableRoles */
    public static boolean assignable(String role) {
        return RoleCatalog.assignableRoles().stream().anyMatch(r -> r.code().equals(role));
    }

    public static boolean known(String role) {
        return RoleCatalog.known(role);
    }
}
