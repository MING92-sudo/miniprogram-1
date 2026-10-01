package com.cqwlw.maintenance.auth;

import java.util.Set;

/**
 * 管理端角色口径：
 * READ  = LEADER/ADMIN/SYS_ADMIN（只读看板/工单/台账/统计/档案查看）；
 * WRITE = ADMIN/SYS_ADMIN（档案维护、手动重报、触发平台同步）。
 * WORKER/UNIT_ADMIN 不使用管理端（小程序端角色不受影响）。
 */
public final class AdminRoles {

    public static final String WORKER = "WORKER";
    public static final String LEADER = "LEADER";
    public static final String UNIT_ADMIN = "UNIT_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String SYS_ADMIN = "SYS_ADMIN";

    private static final Set<String> WRITE = Set.of(ADMIN, SYS_ADMIN);
    private static final Set<String> READ = Set.of(LEADER, ADMIN, SYS_ADMIN);
    private static final Set<String> KNOWN = Set.of(WORKER, LEADER, UNIT_ADMIN, ADMIN, SYS_ADMIN);

    private AdminRoles() {
    }

    /** 档案维护/手动重报/触发同步等写操作 */
    public static boolean canWrite(String role) {
        return role != null && WRITE.contains(role);
    }

    /** 管理端读操作（LEADER 只读） */
    public static boolean canRead(String role) {
        return role != null && READ.contains(role);
    }

    /** 登录接口 client=admin 时允许签发管理端会话的角色 */
    public static boolean canUseAdminConsole(String role) {
        return canRead(role);
    }

    /** 建档允许分配的角色（管理端建档不含 UNIT_ADMIN——使用单位账号走小程序绑定） */
    public static boolean assignable(String role) {
        return role != null && Set.of(WORKER, LEADER, ADMIN, SYS_ADMIN).contains(role);
    }

    public static boolean known(String role) {
        return role != null && KNOWN.contains(role);
    }
}
