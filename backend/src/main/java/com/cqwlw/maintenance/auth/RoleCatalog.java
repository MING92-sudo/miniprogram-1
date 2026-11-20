package com.cqwlw.maintenance.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色目录（docs/14 §3 角色码表）：角色码 → 部门 / 中文名 / 可否登录管理端 / 直属上级。
 * 只做静态映射，不做鉴权判定（判定见 {@link PermissionMatrix}）；未知角色返回 null。
 */
public final class RoleCatalog {

    /** 部门码（docs/14 §2）：三个业务部门平级，PLATFORM 为平台层 */
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

    /**
     * 角色定义。
     *
     * @param parentRole 直属上级角色，仅用于建档/展示口径，不参与鉴权判定（真实的上级是
     *                   {@code sys_employee.manager_id}，见 service/DeptScopeService）
     */
    public record Role(String code, String department, String name, boolean adminConsole, String parentRole) {
    }

    /** 主体类型（P1）：EMPLOYEE 内部员工（平台/安装/维保），CUSTOMER 使用单位（外部客户租户）。 */
    public enum PrincipalType {
        EMPLOYEE, CUSTOMER
    }

    private static final Map<String, Role> ROLES;

    static {
        Map<String, Role> roles = new LinkedHashMap<>();
        for (Role role : List.of(
                // 平台层
                new Role(SYS_ADMIN, PLATFORM, "系统管理员", true, null),
                new Role(ADMIN, PLATFORM, "平台管理员", true, null),
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
        // 规范角色：adminConsole 按 spec §3.1（CLIENT_ADMIN M1 仍 false，M5 才开 Web）
        CANONICAL.put("PLATFORM_ADMIN", new Role("PLATFORM_ADMIN", "PLATFORM", "平台管理员", true, null));
        CANONICAL.put("SYS_ADMIN", new Role("SYS_ADMIN", "PLATFORM", "系统管理员", true, null));
        CANONICAL.put("INST_MGR", new Role("INST_MGR", "INSTALL", "安装部主管", true, null));
        CANONICAL.put("INST_PM", new Role("INST_PM", "INSTALL", "安装部项目负责人", true, "INST_MGR"));
        CANONICAL.put("INST_CLERK", new Role("INST_CLERK", "INSTALL", "安装部资料员", true, "INST_MGR"));
        CANONICAL.put("INSTALLER", new Role("INSTALLER", "INSTALL", "安装员", false, "INST_MGR"));
        CANONICAL.put("MAINT_MGR", new Role("MAINT_MGR", "MAINT", "维保部经理", true, null));
        CANONICAL.put("MAINT_SUP", new Role("MAINT_SUP", "MAINT", "维保主管", true, "MAINT_MGR"));
        CANONICAL.put("MAINT_TECH", new Role("MAINT_TECH", "MAINT", "维保人员", false, "MAINT_SUP"));
        CANONICAL.put("MAINT_BIZ", new Role("MAINT_BIZ", "MAINT", "维保商务", true, "MAINT_MGR"));
        CANONICAL.put("MAINT_QUALITY", new Role("MAINT_QUALITY", "MAINT", "维保质量", true, "MAINT_MGR"));
        // P1：客户角色 department=null（使用单位为外部租户主体，不属内部部门；数据域走 org_id=use_unit 锚点）
        CANONICAL.put("CLIENT_ADMIN", new Role("CLIENT_ADMIN", null, "使用单位管理员", false, null));
        CANONICAL.put("CLIENT_SIGNER", new Role("CLIENT_SIGNER", null, "使用单位签字人", false, null));

        ALIAS.put("ADMIN", "PLATFORM_ADMIN");
        ALIAS.put("SYS_ADMIN", "SYS_ADMIN");
        ALIAS.put("INSTALL_MANAGER", "INST_PM");
        ALIAS.put("INSTALL_CLERK", "INST_CLERK");
        ALIAS.put("INSTALL_WORKER", "INSTALLER");
        ALIAS.put("MAINT_MANAGER", "MAINT_MGR");
        ALIAS.put("LEADER", "MAINT_SUP");
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

    /** 角色码集合（12 个：docs/14 §3 十二行） */
    public static Set<String> codes() {
        return ROLES.keySet();
    }

    /**
     * 部门码：PLATFORM/INSTALL/MAINT；客户主体（P1）与未知角色返回 null。
     *
     * <p>先按历史码查（保持既有文案/口径不变），再按规范码归一 —— 账号管理、人员建档等
     * 入口传的是 13 个规范码（MAINT_TECH/INSTALLER/…），只查历史码会返回 null，
     * 导致新建账号 department 落空、部门数据域谓词失效。
     */
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

    /** 平台层（跨部门全权）：ADMIN/SYS_ADMIN，历史码与规范码（PLATFORM_ADMIN）等价 */
    public static boolean isPlatformRole(String role) {
        String c = canonical(role);
        return "PLATFORM_ADMIN".equals(c) || SYS_ADMIN.equals(c);
    }

    /** 管理端是否可登录（docs/14 §3「可登录端」列 + §10 #1/#2 越权用例） */
    public static boolean canUseAdminConsole(String role) {
        Role r = role(role);
        if (r == null) {
            // v2 规范码账号（INST_PM/MAINT_MGR/MAINT_SUP…）与旧字面量同口径：
            // 只查历史 ROLES 会把规范码判为不可登录，/admin/** 旧两档门禁误拒（docs/14 §3.1 别名表）
            r = canonicalRole(role);
        }
        return r != null && r.adminConsole();
    }

    /**
     * 是否已知角色码：历史码与规范码都算（canonical 先查规范表再经 ALIAS 翻译）。
     * 只查历史 {@code ROLES} 会让 MAINT_TECH/INSTALLER 等规范码被判未知——登录响应、种子数据、
     * 角色分配都已切规范码，判定必须同口径。
     */
    public static boolean known(String role) {
        return canonical(role) != null;
    }

    /**
     * 建档可分配的角色（管理端「新建人员」判定）：仅员工主体。
     * 接受历史码（WORKER…）与规范码（MAINT_TECH…）两种写法；客户主体（CLIENT_* / UNIT_ADMIN）
     * 一律不可在人员目录建档——使用单位账号走「使用单位档案 → 客户账号」。
     */
    public static boolean assignable(String role) {
        return principalTypeOf(role) == PrincipalType.EMPLOYEE;
    }

    /**
     * 建档可分配的角色（管理端「新建人员」下拉）：仅员工主体（P1 按 principalType 过滤）。
     * 使用单位账号不建在员工目录（走 use_unit 账号，docs/14 §9.1），故不可选。
     */
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

    /**
     * 主体类型（P1）：经 canonical 归一后 CLIENT_ADMIN/CLIENT_SIGNER → CUSTOMER，
     * 其余规范码 → EMPLOYEE；null/未知码返回 null。T3—T6 消费此方法判客户。
     */
    public static PrincipalType principalTypeOf(String anyCode) {
        String c = canonical(anyCode);
        if (c == null) {
            return null;
        }
        return "CLIENT_ADMIN".equals(c) || "CLIENT_SIGNER".equals(c)
                ? PrincipalType.CUSTOMER
                : PrincipalType.EMPLOYEE;
    }

    /**
     * P1（T5）：行级客户主体判定——码集非空且全部规范为 CUSTOMER 才是客户；
     * 含任一内部角色（EMPLOYEE）或未知码不视为客户（内部视图全量保持，只剔除客户行）。
     * 与 AuthInterceptor.isCustomer（T3 令牌级）同口径；调用方负责组装生效角色集
     * （主角色 ∪ sys_user_role 多角色集）。判定必须走本方法/主角色集，不得用
     * department 字面量（客户 department 存 UNIT/null 两代值，字面量判定会漏判一半）。
     */
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

    /** 同部门方可兼任（docs/14 R7）；跨部门/未知拒绝。客户角色部门为 null（同为外部租户，视为同域）。 */
    public static boolean sameDepartmentAssignable(String roleA, String roleB) {
        Role a = canonicalRole(roleA);
        Role b = canonicalRole(roleB);
        return a != null && b != null && java.util.Objects.equals(a.department(), b.department());
    }
}
