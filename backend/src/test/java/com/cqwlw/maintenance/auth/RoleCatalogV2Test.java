package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RoleCatalog v2：旧→新别名、13 规范角色、部门/adminConsole、同部门兼任（R7）。 */
class RoleCatalogV2Test {

    @Test
    void legacyCodesAliasToCanonical() {
        assertEquals("PLATFORM_ADMIN", RoleCatalog.canonical("ADMIN"));
        assertEquals("SYS_ADMIN", RoleCatalog.canonical("SYS_ADMIN"));
        assertEquals("INST_PM", RoleCatalog.canonical("INSTALL_MANAGER"));
        assertEquals("INST_CLERK", RoleCatalog.canonical("INSTALL_CLERK"));
        assertEquals("INSTALLER", RoleCatalog.canonical("INSTALL_WORKER"));
        assertEquals("MAINT_MGR", RoleCatalog.canonical("MAINT_MANAGER"));
        assertEquals("MAINT_SUP", RoleCatalog.canonical("LEADER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("WORKER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("ASSISTANT"));
        assertEquals("CLIENT_ADMIN", RoleCatalog.canonical("UNIT_ADMIN"));
        // 新码自映射
        assertEquals("INST_MGR", RoleCatalog.canonical("INST_MGR"));
        assertEquals("CLIENT_SIGNER", RoleCatalog.canonical("CLIENT_SIGNER"));
        assertNull(RoleCatalog.canonical("HACKER"));
        assertNull(RoleCatalog.canonical(null));
    }

    @Test
    void canonicalCodesAreThirteen() {
        assertEquals(Set.of("PLATFORM_ADMIN", "SYS_ADMIN", "INST_MGR", "INST_PM", "INST_CLERK", "INSTALLER",
                "MAINT_MGR", "MAINT_SUP", "MAINT_TECH", "MAINT_BIZ", "MAINT_QUALITY",
                "CLIENT_ADMIN", "CLIENT_SIGNER"), RoleCatalog.canonicalCodes());
    }

    @Test
    void canonicalDepartmentsAndConsole() {
        assertEquals("INSTALL", RoleCatalog.canonicalRole("INST_MGR").department());
        assertEquals("MAINT", RoleCatalog.canonicalRole("MAINT_SUP").department());
        assertNull(RoleCatalog.canonicalRole("CLIENT_SIGNER").department()); // P1：客户部门归 null
        assertEquals("PLATFORM", RoleCatalog.canonicalRole("PLATFORM_ADMIN").department());
        assertTrue(RoleCatalog.canonicalRole("INST_MGR").adminConsole());
        assertTrue(RoleCatalog.canonicalRole("MAINT_MGR").adminConsole());
        // 小程序-only 3 角色
        assertFalse(RoleCatalog.canonicalRole("MAINT_TECH").adminConsole());
        assertFalse(RoleCatalog.canonicalRole("INSTALLER").adminConsole());
        assertFalse(RoleCatalog.canonicalRole("CLIENT_SIGNER").adminConsole());
        // M1：CLIENT_ADMIN 仍不可登录管理端（M5 新能力）
        assertFalse(RoleCatalog.canonicalRole("CLIENT_ADMIN").adminConsole());
        assertFalse(RoleCatalog.canUseAdminConsole("UNIT_ADMIN"));
    }

    @Test
    void sameDepartmentAssignableCrossDepartmentRejected() {
        assertTrue(RoleCatalog.sameDepartmentAssignable("MAINT_SUP", "MAINT_TECH"));
        assertTrue(RoleCatalog.sameDepartmentAssignable("INST_PM", "INST_CLERK"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("MAINT_SUP", "INST_PM"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("CLIENT_ADMIN", "MAINT_TECH"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("HACKER", "MAINT_TECH"));
    }

    @Test
    void legacyCodesUnchangedForCompat() {
        // RbacMatrixTest.roleCatalogMatchesDesignDoc 锁定：codes() 仍 12 旧码
        assertEquals(12, RoleCatalog.codes().size());
        assertTrue(RoleCatalog.codes().contains("ADMIN"));
        assertTrue(RoleCatalog.codes().contains("UNIT_ADMIN"));
        assertEquals("维保班组长", RoleCatalog.nameOf("LEADER"));
    }
}