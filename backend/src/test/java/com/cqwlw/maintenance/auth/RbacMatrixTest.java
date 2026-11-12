package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色目录与权限矩阵（docs/14 §3 角色码表、§6 管理端矩阵、§7 小程序矩阵）：
 * 11 个角色的能力判定、未知码启动即失败、管理端可登录口径。
 */
class RbacMatrixTest {

    private static final List<String> ALL_ROLES = List.of(
            RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
            RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK, RoleCatalog.INSTALL_WORKER,
            RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ, RoleCatalog.LEADER,
            RoleCatalog.MAINT_QUALITY, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
            RoleCatalog.UNIT_ADMIN);

    private static final String ADMIN = RoleCatalog.ADMIN;
    private static final String SYS_ADMIN = RoleCatalog.SYS_ADMIN;

    /** 逐角色断言能力，失败信息带角色码，便于对照 docs/14 §6/§7 */
    private static void assertGrants(String capability, List<String> allowed) {
        for (String role : ALL_ROLES) {
            assertEquals(allowed.contains(role), PermissionMatrix.allows(role, capability),
                    capability + " × " + role);
        }
    }

    @Test
    void consoleTiers() {
        assertGrants(PermissionMatrix.CONSOLE_READ, List.of(
                ADMIN, SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY));
        assertGrants(PermissionMatrix.CONSOLE_WRITE, List.of(ADMIN, SYS_ADMIN));
    }

    @Test
    void orderAndFaultCapabilities() {
        assertGrants(PermissionMatrix.ORDER_READ, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER,
                RoleCatalog.MAINT_QUALITY, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                RoleCatalog.UNIT_ADMIN));
        assertGrants(PermissionMatrix.ORDER_WRITE, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER,
                RoleCatalog.WORKER, RoleCatalog.ASSISTANT));
        assertGrants(PermissionMatrix.FAULT_READ, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER,
                RoleCatalog.MAINT_QUALITY, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                RoleCatalog.UNIT_ADMIN));
        assertGrants(PermissionMatrix.FAULT_WRITE, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER,
                RoleCatalog.WORKER, RoleCatalog.ASSISTANT, RoleCatalog.INSTALL_WORKER,
                RoleCatalog.UNIT_ADMIN));
        assertGrants(PermissionMatrix.RECORD_READ, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER,
                RoleCatalog.MAINT_QUALITY, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                RoleCatalog.UNIT_ADMIN));
    }

    /** docs/14 §8 R3：质量负责人只读异常清单，不可重报 */
    @Test
    void reportRetryExcludesQualityAndInstall() {
        assertGrants(PermissionMatrix.REPORT_RETRY,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER));
        assertFalse(PermissionMatrix.allows(RoleCatalog.MAINT_QUALITY, PermissionMatrix.REPORT_RETRY));
        assertFalse(PermissionMatrix.allows(RoleCatalog.LEADER, PermissionMatrix.REPORT_RETRY));
    }

    @Test
    void contractAndUseUnitCapabilities() {
        assertGrants(PermissionMatrix.CONTRACT_READ, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.INSTALL_MANAGER, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.MAINT_BIZ, RoleCatalog.UNIT_ADMIN));
        assertGrants(PermissionMatrix.CONTRACT_WRITE,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.MAINT_BIZ));
        assertGrants(PermissionMatrix.USEUNIT_READ, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ,
                RoleCatalog.UNIT_ADMIN));
        assertGrants(PermissionMatrix.USEUNIT_WRITE,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.MAINT_BIZ));
    }

    @Test
    void handoverAndFilingCapabilities() {
        List<String> readers = List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_QUALITY);
        assertGrants(PermissionMatrix.HANDOVER_READ, readers);
        assertGrants(PermissionMatrix.FILING_READ, readers);
        // docs/14 §6：安装文员可上传资料（W，端点级在 P3 收敛到 submit-docs）；ADMIN 平台兜底可写，
        // 但节点签字仍由 HandoverService.NODE_ROLES 限角色，写权限不等于代签
        List<String> handoverWriters = List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.INSTALL_WORKER, RoleCatalog.MAINT_MANAGER);
        assertGrants(PermissionMatrix.HANDOVER_WRITE, handoverWriters);
        // 外接建档不涉及安装员现场作业（无接管施工环节）
        List<String> filingWriters = List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER);
        assertGrants(PermissionMatrix.FILING_WRITE, filingWriters);
    }

    @Test
    void templateRegionAndAccountCapabilities() {
        assertGrants(PermissionMatrix.TEMPLATE_WRITE, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_QUALITY));
        assertGrants(PermissionMatrix.PLAN_WRITE, List.of(
                ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER));
        assertGrants(PermissionMatrix.REGION_WRITE, List.of(ADMIN, SYS_ADMIN));
        assertGrants(PermissionMatrix.ACCOUNT_MANAGE,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER));
        assertGrants(PermissionMatrix.LEDGER_WRITE,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.MAINT_MANAGER));
        assertGrants(PermissionMatrix.EMPLOYEE_WRITE,
                List.of(ADMIN, SYS_ADMIN, RoleCatalog.INSTALL_MANAGER, RoleCatalog.MAINT_MANAGER));
    }

    /** 每个能力码至少有一个持有者，防止整列漏填导致"谁都不能用" */
    @Test
    void everyCapabilityHasHolder() {
        for (String capability : PermissionMatrix.capabilities()) {
            assertTrue(ALL_ROLES.stream().anyMatch(role -> PermissionMatrix.allows(role, capability)),
                    "能力码无任何角色持有: " + capability);
        }
    }

    @Test
    void unknownCapabilityFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> PermissionMatrix.requireKnownCapability("order:write2"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.allows(ADMIN, "order:write2"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.allows(ADMIN, null));
        assertDoesNotThrow(() -> PermissionMatrix.requireKnownCapability(PermissionMatrix.ORDER_WRITE));
    }

    @Test
    void unknownRoleIsDenied() {
        assertFalse(PermissionMatrix.allows("HACKER", PermissionMatrix.CONSOLE_READ));
        assertFalse(PermissionMatrix.allows(null, PermissionMatrix.CONSOLE_READ));
        assertFalse(PermissionMatrix.allows("", PermissionMatrix.ORDER_WRITE));
    }

    /** docs/14 §3「可登录端」：只有小程序端的角色登录管理端即 403（§10 用例 1） */
    @Test
    void adminConsoleEntry() {
        for (String role : List.of(RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                RoleCatalog.INSTALL_WORKER, RoleCatalog.UNIT_ADMIN)) {
            assertFalse(RoleCatalog.canUseAdminConsole(role), role);
            assertFalse(PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_READ), role);
        }
        for (String role : ALL_ROLES) {
            if (List.of(RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                    RoleCatalog.INSTALL_WORKER, RoleCatalog.UNIT_ADMIN).contains(role)) {
                continue;
            }
            assertTrue(RoleCatalog.canUseAdminConsole(role), role);
            assertTrue(PermissionMatrix.allows(role, PermissionMatrix.CONSOLE_READ), role);
        }
        assertFalse(RoleCatalog.canUseAdminConsole(null));
        assertFalse(RoleCatalog.canUseAdminConsole("HACKER"));
    }

    /** docs/14 §3 角色码表：部门、中文名、上级 */
    @Test
    void roleCatalogMatchesDesignDoc() {
        assertEquals(Set.of(RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                        RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK, RoleCatalog.INSTALL_WORKER,
                        RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ, RoleCatalog.LEADER,
                        RoleCatalog.MAINT_QUALITY, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                        RoleCatalog.UNIT_ADMIN),
                RoleCatalog.codes(), "角色码表须与 docs/14 §3 十二行逐条一致");
        assertEquals("MAINT", RoleCatalog.departmentOf(RoleCatalog.LEADER));
        assertEquals("MAINT", RoleCatalog.departmentOf(RoleCatalog.WORKER));
        assertEquals("MAINT", RoleCatalog.departmentOf(RoleCatalog.ASSISTANT));
        assertEquals("MAINT", RoleCatalog.departmentOf(RoleCatalog.MAINT_BIZ));
        assertEquals("MAINT", RoleCatalog.departmentOf(RoleCatalog.MAINT_QUALITY));
        assertEquals("INSTALL", RoleCatalog.departmentOf(RoleCatalog.INSTALL_CLERK));
        assertEquals("UNIT", RoleCatalog.departmentOf(RoleCatalog.UNIT_ADMIN));
        assertEquals("PLATFORM", RoleCatalog.departmentOf(RoleCatalog.ADMIN));
        assertEquals("PLATFORM", RoleCatalog.departmentOf(RoleCatalog.SYS_ADMIN));

        assertEquals("维保班组长", RoleCatalog.nameOf(RoleCatalog.LEADER));
        assertEquals("平台管理员", RoleCatalog.nameOf(RoleCatalog.ADMIN));
        assertEquals(RoleCatalog.MAINT_MANAGER, RoleCatalog.role(RoleCatalog.LEADER).parentRole());
        assertEquals(RoleCatalog.LEADER, RoleCatalog.role(RoleCatalog.WORKER).parentRole());

        assertTrue(RoleCatalog.known(RoleCatalog.INSTALL_WORKER));
        assertFalse(RoleCatalog.known("HACKER"));
        assertFalse(RoleCatalog.known(null));
        assertNull(RoleCatalog.departmentOf("HACKER"));
        assertNull(RoleCatalog.nameOf(null));
    }
}
