package com.cqwlw.maintenance.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只做静态映射，不做鉴权判定（判定见 {@link PermissionMatrix}）； */
public final class RoleCatalog {

    public static final String PLATFORM = "PLATFORM";
    public static final String INSTALL = "INSTALL";
    public static final String MAINT = "MAINT";
    public static final String UNIT = "UNIT";

    public static final String ADMIN = "ADMIN";
    public static final String SYS_ADMIN = "SYS_ADMIN";
    public static final String INSTALL_MANAGER = "INSTALL_MANAGER";
    public static final String INSTALL_CLERK = "INSTALL_CLERK";
    public static final String INSTALL_WORKER = "INSTALL_WORKER";
    public static final String MAINT_MANAGER = "MAINT_MANAGER";
    public static final String MAINT_BIZ = "MAINT_BIZ";
    public static final String LEADER = "LEADER";
    public static final String MAINT_QUALITY = "MAINT_QUALITY";
    public static final String WORKER = "WORKER";
    public static final String ASSISTANT = "ASSISTANT";
    public static final String UNIT_ADMIN = "UNIT_ADMIN";

    /** parentRole 仅用于展示，不参与鉴权。 */
    public record Role(String code, String department, String name, boolean adminConsole, String parentRole) {
    }

    public enum PrincipalType {
        EMPLOYEE, CUSTOMER
    }

    private static final Map<String, Role> ROLES;

    static {
        Map<String, Role> roles = new LinkedHashMap<>();
        for (Role role : List.of(
                new Role(ADMIN, PLATFORM, "维保部管理员", true, null),
                // 安装部
                new Role(INSTALL_MANAGER, INSTALL, "安装项目经理", true, ADMIN),
                new Role(INSTALL_CLERK, INSTALL, "安装文员", true, INSTALL_MANAGER),
                new Role(INSTALL_WORKER, INSTALL, "安装员", false, INSTALL_MANAGER),
                // 维保部
                new Role(MAINT_MANAGER, MAINT, "维保经理", true, ADMIN),
                new Role(MAINT_BIZ, MAINT, "维保商务", true, MAINT_MANAGER),
                new Role(LEADER, MAINT, "维保班组长", true, MAINT_MANAGER),
                new Role(MAINT_QUALITY, MAINT, "维保质量负责人", true, MAINT_MANAGER),
                new Role(WORKER, MAINT, "维保人员", false, LEADER),
                new Role(ASSISTANT, MAINT, "配合维保人员", false, LEADER),
                // 使用单位（对 ADMIN 报备，非员工目录内的上下级）
                new Role(UNIT_ADMIN, UNIT, "使用单位安全管理员", false, null))) {
            roles.put(role.code(), role);
        }
        ROLES = Map.copyOf(roles);
    }

    /** 规范角色（13）：code→Role。旧码经 ALIAS 翻译到此表（spec §3.1）。 */
    private static final java.util.Map<String, Role> CANONICAL = new java.util.LinkedHashMap<>();
    /** 旧码→规范码别名（spec §3.1 表）。 */
    private static final java.util.Map<String, String> ALIAS = new java.util.LinkedHashMap<>();

    static {
        CANONICAL.put("PLATFORM_ADMIN", new Role("PLATFORM_ADMIN", "PLATFORM", "平台管理员", true, null));
        CANONICAL.put("INST_MGR", new Role("INST_MGR", "INSTALL", "安装部主管", true, null));
        CANONICAL.put("INST_PM", new Role("INST_PM", "INSTALL", "安装部项目负责人", true, "INST_MGR"));
        CANONICAL.put("INST_CLERK", new Role("INST_CLERK", "INSTALL", "安装部资料员", true, "INST_MGR"));
        CANONICAL.put("INSTALLER", new Role("INSTALLER", "INSTALL", "安装员", false, "INST_MGR"));
        CANONICAL.put("MAINT_MGR", new Role("MAINT_MGR", "MAINT", "维保部经理", true, null));
        CANONICAL.put("MAINT_SUP", new Role("MAINT_SUP", "MAINT", "维保主管", true, "MAINT_MGR"));
        CANONICAL.put("LEADER", new Role("LEADER", "MAINT", "维保班组长", true, "MAINT_SUP"));
        CANONICAL.put("MAINT_TECH", new Role("MAINT_TECH", "MAINT", "维保人员", false, "MAINT_SUP"));
        CANONICAL.put("MAINT_BIZ", new Role("MAINT_BIZ", "MAINT", "维保商务", true, "MAINT_MGR"));
        CANONICAL.put("MAINT_QUALITY", new Role("MAINT_QUALITY", "MAINT", "维保质量负责人", true, "MAINT_MGR"));
        CANONICAL.put("CLIENT_ADMIN", new Role("CLIENT_ADMIN", null, "使用单位管理员", false, null));
        CANONICAL.put("CLIENT_SIGNER", new Role("CLIENT_SIGNER", null, "使用单位签字人", false, null));

        ALIAS.put("ADMIN", "PLATFORM_ADMIN");
        ALIAS.put("SYS_ADMIN", "PLATFORM_ADMIN");
        ALIAS.put("INSTALL_MANAGER", "INST_PM");
        ALIAS.put("INSTALL_CLERK", "INST_CLERK");
        ALIAS.put("INSTALL_WORKER", "INSTALLER");
        ALIAS.put("MAINT_MANAGER", "MAINT_MGR");
        ALIAS.put("WORKER", "MAINT_TECH");
        ALIAS.put("ASSISTANT", "MAINT_TECH");
        ALIAS.put("MAINT_BIZ", "MAINT_BIZ");
        ALIAS.put("MAINT_QUALITY", "MAINT_QUALITY");
        ALIAS.put("UNIT_ADMIN", "CLIENT_ADMIN");
    }

    private RoleCatalog() {
    }

    /** 角色定义；未知角色返回 null */
    public static Role role(String role) {
        return role == null ? null : ROLES.get(role);
    }

    public static Set<String> codes() {
        return ROLES.keySet();
    }

    /** 兼容历史角色码，并归一到规范角色码。 */
    public static String departmentOf(String role) {
        if (principalTypeOf(role) == PrincipalType.CUSTOMER) {
            return null; // P1：使用单位是外部租户，不再投影 UNIT 部门（旧 ROLES 表字段保留，仅供历史展示）
        }
        Role r = role(role);
        if (r == null) {
            r = canonicalRole(role);
        }
        return r == null ? null : r.department();
    }

    /** 中文名；历史码优先（保留既有文案），再按规范码归一；未知角色返回 null */
    public static String nameOf(String role) {
        Role r = role(role);
        if (r == null) {
            r = canonicalRole(role);
        }
        return r == null ? null : r.name();
    }

    /** 平台层（跨部门全权）：唯一全权角色 PLATFORM_ADMIN（历史 ADMIN/SYS_ADMIN 经 ALIAS 归一） */
    public static boolean isPlatformRole(String role) {
        return "PLATFORM_ADMIN".equals(canonical(role));
    }

    /** 管理端是否可登录 */
    public static boolean canUseAdminConsole(String role) {
        Role r = role(role);
        if (r == null) {
            r = canonicalRole(role);
        }
        return r != null && r.adminConsole();
    }

    /** 同时识别历史码和规范码。 */
    public static boolean known(String role) {
        return canonical(role) != null;
    }

    /** 建档可分配的角色（管理端「新建人员」判定）：仅员工主体。 */
    public static boolean assignable(String role) {
        return principalTypeOf(role) == PrincipalType.EMPLOYEE;
    }

    public static java.util.List<Role> assignableRoles() {
        return ROLES.values().stream()
                .filter(r -> principalTypeOf(r.code()) == PrincipalType.EMPLOYEE)
                .toList();
    }

    /** 部门中文名（前端/接口展示用）：PLATFORM 平台层、INSTALL 安装部、MAINT 维保部、UNIT 使用单位 */
    public static String departmentName(String department) {
        if (department == null) {
            return "";
        }
        return switch (department) {
            case PLATFORM -> "平台层";
            case INSTALL -> "安装部";
            case MAINT -> "维保部";
            case UNIT -> "使用单位";
            default -> department;
        };
    }

    /** 旧码/新码 → 规范码；未知返回 null。 */
    public static String canonical(String anyCode) {
        if (anyCode == null) {
            return null;
        }
        if (CANONICAL.containsKey(anyCode)) {
            return anyCode;
        }
        return ALIAS.get(anyCode);
    }

    /** 13 规范码集合。 */
    public static java.util.Set<String> canonicalCodes() {
        return java.util.Collections.unmodifiableSet(CANONICAL.keySet());
    }

    /** 规范角色 record；未知返回 null。 */
    public static Role canonicalRole(String anyCode) {
        String c = canonical(anyCode);
        return c == null ? null : CANONICAL.get(c);
    }

    public static PrincipalType principalTypeOf(String anyCode) {
        String c = canonical(anyCode);
        if (c == null) {
            return null;
        }
        return "CLIENT_ADMIN".equals(c) || "CLIENT_SIGNER".equals(c)
                ? PrincipalType.CUSTOMER
                : PrincipalType.EMPLOYEE;
    }

    /** 判定必须走本方法/主角色集，不得用department 字面量（客户 department 存 UNIT/null 两代值，字面量判定会漏判一半）。 */
    public static boolean isCustomerPrincipal(java.util.Collection<String> anyCodes) {
        if (anyCodes == null || anyCodes.isEmpty()) {
            return false;
        }
        for (String code : anyCodes) {
            if (principalTypeOf(code) != PrincipalType.CUSTOMER) {
                return false;
            }
        }
        return true;
    }

    public static boolean sameDepartmentAssignable(String roleA, String roleB) {
        Role a = canonicalRole(roleA);
        Role b = canonicalRole(roleB);
        return a != null && b != null && java.util.Objects.equals(a.department(), b.department());
    }
}
