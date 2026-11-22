package com.cqwlw.maintenance.auth;

import java.util.Map;
import java.util.Set;

/** 兼容期二值判定 {@link #allows} 精确复现冻结 GRANTS（RbacMatrixTest oracle）； */
public final class LegacyCapabilityTranslator {

    private LegacyCapabilityTranslator() {
    }

    private static final Map<String, Set<String>> MAP = Map.ofEntries(
            Map.entry("console:read", Set.of()),
            Map.entry("console:write", Set.of("config:manage")),
            Map.entry("order:read", Set.of("plan:view", "record:view", "task:accept")),
            Map.entry("order:write", Set.of("task:accept", "checklist:fill", "record:submit", "record:sign_tech")),
            Map.entry("fault:read", Set.of("order:view_pool", "order:view_own")),
            Map.entry("fault:write", Set.of("order:create", "order:dispatch", "order:arrive", "order:process",
                    "order:submit", "order:confirm", "order:close", "order:cancel")),
            Map.entry("record:read", Set.of("record:view")),
            Map.entry("report:retry", Set.of("report:retry")),
            Map.entry("plan:write", Set.of("plan:generate", "task:assign_partner")),
            Map.entry("template:write", Set.of("template:write", "template:toggle")),
            Map.entry("ledger:read", Set.of("elevator:view", "project:view")),
            Map.entry("ledger:write", Set.of("elevator:edit", "elevator:transfer")),
            Map.entry("employee:read", Set.of("user:view")),
            Map.entry("employee:write", Set.of("user:create", "user:disable", "user:reset_password")),
            Map.entry("contract:read", Set.of("contract:view")),
            Map.entry("contract:write", Set.of("contract:edit")),
            Map.entry("useunit:read", Set.of("client:elevator_view")),
            Map.entry("useunit:write", Set.of("client:edit")),
            Map.entry("handover:read", Set.of("handover:view")),
            Map.entry("handover:write", Set.of("handover:create", "handover:edit", "handover:submit",
                    "handover:doc_upload", "handover:doc_delete", "handover:review", "handover:receive",
                    "handover:inspect", "handover:rectify", "handover:sign_install", "handover:sign_maint", "handover:void")),
            Map.entry("region:write", Set.of("region:view", "region:write")),
            Map.entry("account:manage", Set.of("user:create", "user:disable", "invite:manage"))
    );

    public static Set<String> translate(String oldCode) {
        Set<String> mapped = MAP.get(oldCode);
        if (mapped == null) {
            throw new IllegalArgumentException("未知旧权限码: " + oldCode);
        }
        return mapped;
    }

    /** 旧别名二值判定（兼容期）。 */
    public static boolean allows(String role, String oldCode) {
        if (role == null || oldCode == null || !MAP.containsKey(oldCode)) {
            return false;
        }
        if ("console:read".equals(oldCode)) {
            return consoleReadGate(role);
        }
        Set<String> holders = PermissionMatrix.holders(oldCode);
        if (holders.contains(role)) {
            return true;
        }
        String canonical = RoleCatalog.canonical(role);
        String legacy = canonical == null ? null : LEGACY_LITERAL.get(canonical);
        return legacy != null && holders.contains(legacy);
    }

    private static final Map<String, String> LEGACY_LITERAL = Map.ofEntries(
            Map.entry("PLATFORM_ADMIN", "ADMIN"),
            Map.entry("SYS_ADMIN", "SYS_ADMIN"),
            Map.entry("INST_PM", "INSTALL_MANAGER"),
            Map.entry("INST_CLERK", "INSTALL_CLERK"),
            Map.entry("INSTALLER", "INSTALL_WORKER"),
            Map.entry("MAINT_MGR", "MAINT_MANAGER"),
            Map.entry("MAINT_SUP", "LEADER"),
            Map.entry("MAINT_TECH", "WORKER"),
            Map.entry("MAINT_BIZ", "MAINT_BIZ"),
            Map.entry("MAINT_QUALITY", "MAINT_QUALITY"),
            Map.entry("CLIENT_ADMIN", "UNIT_ADMIN")
    );

    private static boolean consoleReadGate(String role) {
        if (!RoleCatalog.canUseAdminConsole(role)) {
            return false;
        }
        String canonical = RoleCatalog.canonical(role);
        if (canonical == null) {
            return false;
        }
        for (String code : PermissionMatrix.canonicalCapabilities()) {
            if (code.endsWith(":view") || code.endsWith(":view_pool") || code.endsWith(":view_own")) {
                PermissionMatrix.TriState st = PermissionMatrix.state(canonical, code);
                if (st == PermissionMatrix.TriState.Y || st == PermissionMatrix.TriState.C) {
                    return true;
                }
            }
        }
        return false;
    }
}