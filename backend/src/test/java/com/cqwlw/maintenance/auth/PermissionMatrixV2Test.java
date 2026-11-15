package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 矩阵 v2：三态 state、allowsNew 要求 Y、86 码、fail-closed、24 旧常量仍在。 */
class PermissionMatrixV2Test {

    @Test
    void canonicalCapabilitiesAreEightySix() {
        assertEquals(86, PermissionMatrix.canonicalCapabilities().size());
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("handover:view"));
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("record:spotcheck"));
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("user:reset_password"));
    }

    @Test
    void triStateSamplesFromRolesDoc() {
        // roles-permissions.md §4.1：安装主管✔ handover:review，资料员无
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("INST_MGR", "handover:review"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("INST_CLERK", "handover:review"));
        // §4.1 handover:view：资料员△=C
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("INST_CLERK", "handover:view"));
        // §4.3 record:view：CA 目标态无（M0 row7），旧别名 record:read 仍含 UA（Task 6 冻结）
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "record:view"));
        // M0 §2.2 扩展：MQ record:spotcheck✔、MB client:edit✔、IW handover:inspect✔
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_QUALITY", "record:spotcheck"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_BIZ", "client:edit"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("INSTALLER", "handover:inspect"));
        // SYS_ADMIN 镜像 PLATFORM_ADMIN
        assertEquals(PermissionMatrix.state("PLATFORM_ADMIN", "config:manage"),
                PermissionMatrix.state("SYS_ADMIN", "config:manage"));
    }

    @Test
    void allowsNewRequiresY() {
        assertTrue(PermissionMatrix.allowsNew("INST_MGR", "handover:review"));
        assertFalse(PermissionMatrix.allowsNew("INST_CLERK", "handover:view")); // C 不是 Y
        assertFalse(PermissionMatrix.allowsNew("MAINT_TECH", "config:manage"));
    }

    /** 用户裁决 2026-11-15：SYS_ADMIN 全权限；外接建档写归维保部；安装员条件读移交单。 */
    @Test
    void userRulings20261115() {
        for (String code : PermissionMatrix.canonicalCapabilities()) {
            assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("SYS_ADMIN", code), code);
        }
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_MGR", "filing:write"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_SUP", "filing:write"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_BIZ", "filing:write"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("INST_PM", "filing:write"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("INST_CLERK", "filing:write"));
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("INST_PM", "filing:view"));
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("INSTALLER", "handover:view"));
    }

    @Test
    void unknownRoleDeniedUnknownCodeFailsLoudly() {
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("HACKER", "handover:view"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state(null, "handover:view"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.state("INST_MGR", "no:such_code"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.allowsNew("INST_MGR", null));
    }

    @Test
    void legacyConstantsAndCapabilitiesPreserved() {
        // RbacMatrixTest 锁定：24 旧码仍在
        assertEquals(24, PermissionMatrix.capabilities().size());
        assertEquals("region:write", PermissionMatrix.REGION_WRITE);
        assertEquals("console:read", PermissionMatrix.CONSOLE_READ);
    }
}