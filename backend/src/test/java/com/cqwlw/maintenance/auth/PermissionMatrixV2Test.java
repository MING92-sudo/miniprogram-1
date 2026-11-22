package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionMatrixV2Test {

    @Test
    void canonicalCapabilitiesAreEightyFour() {
        assertEquals(84, PermissionMatrix.canonicalCapabilities().size());
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("handover:view"));
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("contract:view"));
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
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "record:view"));
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

    @Test
    void userRulings20261115() {
        for (String code : PermissionMatrix.canonicalCapabilities()) {
            assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("SYS_ADMIN", code), code);
        }
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("INSTALLER", "handover:view"));
        // 维保人员现场验收需读移交单（菜单「移交验收」下发 MAINT 全工作区，权限面=渲染面）
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("MAINT_TECH", "handover:view"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_TECH", "handover:inspect"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "handover:view"));
    }

    /** 内部码全收回（相关码仅平台管理员经全 Y 循环持有）。 */
    @Test
    void clientMinimalCapabilitySetP1() {
        // 客户最小集
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:sign"));   // 原 DENY→Y
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_SIGNER", "client:sign"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:elevator_view"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:download"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_SIGNER", "client:elevator_view"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_SIGNER", "client:download"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:report"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("CLIENT_SIGNER", "client:report"));
        // 默认关闭
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:user_manage")); // 原 Y
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:evaluate"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:invite"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_SIGNER", "client:user_manage"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_SIGNER", "client:evaluate"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_SIGNER", "client:invite"));
        // 内部码全收回（抽样）
        for (String code : new String[]{"user:view", "user:create", "user:disable", "stats:view", "stats:export",
                "audit:view", "role:assign", "invite:manage", "order:create", "order:view_own", "order:confirm",
                "record:view", "record:sign", "record:refuse", "record:export", "handover:view", "handover:export",
                "project:view", "contract:view", "elevator:view", "elevator:qr", "elevator:export", "plan:view"}) {
            assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", code), code);
            assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_SIGNER", code), code);
        }
        assertEquals(84, PermissionMatrix.canonicalCapabilities().size()); // 外接建档两码移除，其他码不变
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("SYS_ADMIN", "client:user_manage")); // SA 全 Y 保留
    }

    @Test
    void unknownRoleDeniedUnknownCodeFailsLoudly() {
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("HACKER", "handover:view"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state(null, "handover:view"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.state("INST_MGR", "no:such_code"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.allowsNew("INST_MGR", null));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.state("MAINT_MGR", "filing:write"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.state("MAINT_MGR", "filing:view"));
    }

    @Test
    void legacyConstantsAndCapabilitiesPreserved() {
        // RbacMatrixTest 锁定：除外接建档两个旧码随模块移除外，其余旧码保留
        assertEquals(22, PermissionMatrix.capabilities().size());
        assertEquals("region:write", PermissionMatrix.REGION_WRITE);
        assertEquals("console:read", PermissionMatrix.CONSOLE_READ);
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.requireKnownCapability("filing:read"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.requireKnownCapability("filing:write"));
    }
}