package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleCatalogV2Test {

    @Test
    void legacyCodesAliasToCanonical() {
        assertEquals("PLATFORM_ADMIN", RoleCatalog.canonical("ADMIN"));
        assertEquals("PLATFORM_ADMIN", RoleCatalog.canonical("SYS_ADMIN"));
        assertEquals("INST_PM", RoleCatalog.canonical("INSTALL_MANAGER"));
        assertEquals("INST_CLERK", RoleCatalog.canonical("INSTALL_CLERK"));
        assertEquals("INSTALLER", RoleCatalog.canonical("INSTALL_WORKER"));
        assertEquals("MAINT_MGR", RoleCatalog.canonical("MAINT_MANAGER"));
        assertEquals("LEADER", RoleCatalog.canonical("LEADER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("WORKER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("ASSISTANT"));
        assertEquals("CLIENT_ADMIN", RoleCatalog.canonical("UNIT_ADMIN"));
        // 新码自映射
        assertEquals("INST_MGR", RoleCatalog.canonical("INST_MGR"));
        assertEquals("CLIENT_SIGNER", RoleCatalog.canonical("CLIENT_SIGNER"));
        assertNull(RoleCatalog.canonical("HACKER"));
        assertNull(RoleCatalog.canonical(null));
    }

    /** 规范码输入同样能解析部门/名称：账号管理与人员建档传的就是 13 个规范码 */
    @Test
    void canonicalCodesResolveDepartmentAndName() {
        assertEquals("MAINT", RoleCatalog.departmentOf("MAINT_TECH"));
        assertEquals("INSTALL", RoleCatalog.departmentOf("INSTALLER"));
        assertEquals("PLATFORM", RoleCatalog.departmentOf("PLATFORM_ADMIN"));
        assertNull(RoleCatalog.departmentOf("CLIENT_ADMIN"));
        assertEquals("维保人员", RoleCatalog.nameOf("MAINT_TECH"));
        assertEquals("安装员", RoleCatalog.nameOf("INSTALLER"));
        // 历史码文案保持原样（不因归一被改写成规范名）
        assertEquals("维保班组长", RoleCatalog.nameOf("LEADER"));
    }

    /** 平台层判定：历史码 ADMIN/SYS_ADMIN 与规范码 PLATFORM_ADMIN 等价 */
    @Test
    void platformRoleAcceptsLegacyAndCanonicalCodes() {
        assertTrue(RoleCatalog.isPlatformRole("ADMIN"));
        assertTrue(RoleCatalog.isPlatformRole("PLATFORM_ADMIN"));
        assertTrue(RoleCatalog.isPlatformRole("SYS_ADMIN"));
        assertFalse(RoleCatalog.isPlatformRole("MAINT_TECH"));
        assertFalse(RoleCatalog.isPlatformRole(null));
    }

    @Test
    void canonicalCodesAreThirteen() {
        assertEquals(Set.of("PLATFORM_ADMIN", "INST_MGR", "INST_PM", "INST_CLERK", "INSTALLER",
                "MAINT_MGR", "MAINT_SUP", "LEADER", "MAINT_TECH", "MAINT_BIZ", "MAINT_QUALITY",
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
        // RbacMatrixTest.roleCatalogMatchesDesignDoc 锁定：codes() 旧码 11（SYS_ADMIN 已并）
        assertEquals(11, RoleCatalog.codes().size());
        assertTrue(RoleCatalog.codes().contains("ADMIN"));
        assertTrue(RoleCatalog.codes().contains("UNIT_ADMIN"));
        assertEquals("维保班组长", RoleCatalog.nameOf("LEADER"));
    }

    /** 系统其余部分（登录响应、种子、角色分配）已切规范码，判定必须同口径； */
    @Test
    void canonicalCodesKnownAndAssignable() {
        assertTrue(RoleCatalog.known("MAINT_TECH"));
        assertTrue(RoleCatalog.known("INSTALLER"));
        assertTrue(RoleCatalog.known("PLATFORM_ADMIN"));
        assertTrue(RoleCatalog.known("WORKER"), "历史码继续可用");

        assertTrue(RoleCatalog.assignable("MAINT_TECH"));
        assertTrue(RoleCatalog.assignable("INSTALLER"));
        assertTrue(RoleCatalog.assignable("WORKER"), "历史码与之等价");

        // 客户主体不在人员目录建档（走使用单位档案 → 客户账号）
        assertFalse(RoleCatalog.assignable("CLIENT_ADMIN"));
        assertFalse(RoleCatalog.assignable("CLIENT_SIGNER"));
        assertFalse(RoleCatalog.assignable("UNIT_ADMIN"));
        assertFalse(RoleCatalog.assignable("HACKER"));
        assertFalse(RoleCatalog.assignable(null));
    }
}