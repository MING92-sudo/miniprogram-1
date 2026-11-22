package com.cqwlw.maintenance.auth;

import java.util.Set;

/** 管理端角色口径：READ = 可登录管理端的 8 个角色（console:read），LEADER 只读； */
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

    public static boolean assignable(String role) {
        return RoleCatalog.assignable(role);
    }

    public static boolean known(String role) {
        return RoleCatalog.known(role);
    }
}
