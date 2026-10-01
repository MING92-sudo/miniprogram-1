package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AdminRoles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端角色矩阵（docs/09 §三）：LEADER 只读；ADMIN/SYS_ADMIN 可写；
 * WORKER/UNIT_ADMIN 不使用管理端。
 */
class AdminRolesTest {

    @Test
    void roleMatrix() {
        assertTrue(AdminRoles.canRead("LEADER"));
        assertTrue(AdminRoles.canRead("ADMIN"));
        assertTrue(AdminRoles.canRead("SYS_ADMIN"));
        assertFalse(AdminRoles.canWrite("LEADER"));
        assertTrue(AdminRoles.canWrite("ADMIN"));
        assertTrue(AdminRoles.canWrite("SYS_ADMIN"));
        assertFalse(AdminRoles.canRead("WORKER"));
        assertFalse(AdminRoles.canRead("UNIT_ADMIN"));
        assertFalse(AdminRoles.canUseAdminConsole("WORKER"));
        assertFalse(AdminRoles.canUseAdminConsole(null));
    }

    @Test
    void assignableRolesForEmployeeArchive() {
        assertTrue(AdminRoles.assignable("WORKER"));
        assertTrue(AdminRoles.assignable("LEADER"));
        assertTrue(AdminRoles.assignable("ADMIN"));
        assertTrue(AdminRoles.assignable("SYS_ADMIN"));
        // 使用单位账号走小程序绑定，不允许管理端直接建档
        assertFalse(AdminRoles.assignable("UNIT_ADMIN"));
    }
}
