package com.cqwlw.maintenance.auth;

import java.util.Map;
import java.util.Set;

/**
 * 旧 24 码 → 新 71+ 码翻译（M0 §2.1 唯一映射源）。
 * 兼容期二值判定 {@link #allows} 精确复现冻结 GRANTS（RbacMatrixTest oracle）；
 * console:read 经 adminConsole 门控计算（M1-4），order/fault 语义反转（M0 §2.0，Blocker）。
 * 新码三态事实源在 PermissionMatrix.STATES；本类只做桥接，不维护第二份授权事实。
 */
public final class LegacyCapabilityTranslator {

    private LegacyCapabilityTranslator() {
    }

    /** 旧码→新码集（M0 §2.1 主映射）。console:read 无独立新码→空集（判定走门控）。 */
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
            Map.entry("filing:read", Set.of("filing:view")),
            Map.entry("filing:write", Set.of("filing:write")),
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

    /**
     * 旧别名二值判定（兼容期）。冻结 GRANTS 是 oracle；console:read 用 adminConsole 门控
     * 计算并断言与冻结集一致。未知角色→false，未知/null 码→false（与 PermissionMatrix.allows 现状一致）。
     */
    public static boolean allows(String role, String oldCode) {
        if (role == null || oldCode == null || !MAP.containsKey(oldCode)) {
            return false;
        }
        if ("console:read".equals(oldCode)) {
            return consoleReadGate(role);
        }
        // 其余 23 码：冻结授权（M1 保留旧行为，目标态差异推迟 M5）
        return PermissionMatrix.holders(oldCode).contains(role);
    }

    /** console:read(role) = adminConsole(role) ∧ 任一域 view∈{Y,C}（M1-4）。 */
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