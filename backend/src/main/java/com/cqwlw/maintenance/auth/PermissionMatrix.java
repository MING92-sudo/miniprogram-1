package com.cqwlw.maintenance.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 功能权限矩阵（docs/14 §6 管理端矩阵 + §7 小程序矩阵）：角色 × 能力码 → 允许/拒绝。
 * 能力码只表达"允许做这个动作"；作用域（本部门/本组/本人/本使用单位）由
 * service/DeptScopeService 的谓词收敛，能力码本身不带范围。
 * 类初始化即校验矩阵里的角色码与能力码，拼错直接启动失败（docs/14 §9.3，防止静默放行）。
 */
public final class PermissionMatrix {

    /** 管理端读操作（任一可登录管理端的角色） */
    public static final String CONSOLE_READ = "console:read";
    /** 管理端粗粒度写档（P1 保持旧两档门禁；细粒度写入 P3 由 {@code @RequirePerm} 按能力码收敛） */
    public static final String CONSOLE_WRITE = "console:write";
    /** 工单列表/详情/签到/清单/签退 */
    public static final String ORDER_READ = "order:read";
    public static final String ORDER_WRITE = "order:write";
    /** 急修单 */
    public static final String FAULT_READ = "fault:read";
    public static final String FAULT_WRITE = "fault:write";
    /** 维保记录读（使用单位本梯确认签字属写动作，能力码待 P4 使用单位门户补） */
    public static final String RECORD_READ = "record:read";
    /** 上报异常清单重报（docs/14 §8 R3：质量负责人不可重报） */
    public static final String REPORT_RETRY = "report:retry";
    /** 计划调度：派单/延期/转派（LEADER 为本组范围，见 AdminRoleInterceptor 例外分支） */
    public static final String PLAN_WRITE = "plan:write";
    /** 模板管理（MAINT_QUALITY 仅启停，docs/14 §5） */
    public static final String TEMPLATE_WRITE = "template:write";
    /** 电梯台账/档案 */
    public static final String LEDGER_READ = "ledger:read";
    public static final String LEDGER_WRITE = "ledger:write";
    /** 人员档案（本部门） */
    public static final String EMPLOYEE_READ = "employee:read";
    public static final String EMPLOYEE_WRITE = "employee:write";
    /** 维保合同 */
    public static final String CONTRACT_READ = "contract:read";
    public static final String CONTRACT_WRITE = "contract:write";
    /** 使用单位档案 */
    public static final String USEUNIT_READ = "useunit:read";
    public static final String USEUNIT_WRITE = "useunit:write";
    /** 移交单 */
    public static final String HANDOVER_READ = "handover:read";
    public static final String HANDOVER_WRITE = "handover:write";
    /** 外接建档单 */
    public static final String FILING_READ = "filing:read";
    public static final String FILING_WRITE = "filing:write";
    /** 区域主数据（docs/14 §6 仅 ADMIN） */
    public static final String REGION_WRITE = "region:write";
    /** 账号启停 / 重置密码 */
    public static final String ACCOUNT_MANAGE = "account:manage";

    private static final Set<String> CAPABILITIES = Set.of(
            CONSOLE_READ, CONSOLE_WRITE,
            ORDER_READ, ORDER_WRITE,
            FAULT_READ, FAULT_WRITE,
            RECORD_READ, REPORT_RETRY,
            PLAN_WRITE, TEMPLATE_WRITE,
            LEDGER_READ, LEDGER_WRITE,
            EMPLOYEE_READ, EMPLOYEE_WRITE,
            CONTRACT_READ, CONTRACT_WRITE,
            USEUNIT_READ, USEUNIT_WRITE,
            HANDOVER_READ, HANDOVER_WRITE,
            FILING_READ, FILING_WRITE,
            REGION_WRITE, ACCOUNT_MANAGE);

    private static final Map<String, Set<String>> GRANTS;

    static {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        // 管理端入口：§6 有列的角色（= docs/14 §3 可登录管理端）；WORKER/ASSISTANT/INSTALL_WORKER/UNIT_ADMIN 登录即 403
        grant(grants, CONSOLE_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY);
        // §6：写档仍只有平台层（P1 不改现有 5 角色放行结果；部门内写权限由细粒度能力码表达）
        grant(grants, CONSOLE_WRITE, RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN);

        // §6 工单列表/详情 + §7 工单（本人/本组）+ §4 使用单位仅本梯
        grant(grants, ORDER_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY,
                RoleCatalog.WORKER, RoleCatalog.ASSISTANT, RoleCatalog.UNIT_ADMIN);
        // §7 签到/清单/签退：本人作业；§6 管理端写由平台层与维保经理承担
        grant(grants, ORDER_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.LEADER, RoleCatalog.WORKER, RoleCatalog.ASSISTANT);

        grant(grants, FAULT_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY,
                RoleCatalog.WORKER, RoleCatalog.ASSISTANT, RoleCatalog.UNIT_ADMIN);
        // §7 急修单登记：现场报修（安装员）、本组、本人、本梯报修（使用单位）
        grant(grants, FAULT_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.LEADER, RoleCatalog.WORKER, RoleCatalog.ASSISTANT,
                RoleCatalog.INSTALL_WORKER, RoleCatalog.UNIT_ADMIN);
        // §4 记录读：维保部全部口径 + 使用单位本梯；§8 R4 商务不见现场作业数据
        grant(grants, RECORD_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY,
                RoleCatalog.WORKER, RoleCatalog.ASSISTANT, RoleCatalog.UNIT_ADMIN);

        // §6 上报异常清单（重报）+ §8 R3：质量负责人只读，不可重报
        grant(grants, REPORT_RETRY,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER);
        // §6 计划调度：班组长 W（本组），范围由 DeptScopeService + 单据归属校验收敛（§5）
        grant(grants, PLAN_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER);
        // §6 模板管理：质量负责人仅启停（§5），启停动作的判定在 P4 抽检模块
        grant(grants, TEMPLATE_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_QUALITY);

        // §6 电梯台账/档案 + §4 安装部本部门读、使用单位本梯读
        grant(grants, LEDGER_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.MAINT_BIZ,
                RoleCatalog.LEADER, RoleCatalog.MAINT_QUALITY, RoleCatalog.UNIT_ADMIN);
        grant(grants, LEDGER_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER);

        // §6 人员档案（本部门）
        grant(grants, EMPLOYEE_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.LEADER);
        grant(grants, EMPLOYEE_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.MAINT_MANAGER);

        // §6 维保合同 + §4 使用单位本单位合同读
        grant(grants, CONTRACT_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.MAINT_MANAGER,
                RoleCatalog.MAINT_BIZ, RoleCatalog.UNIT_ADMIN);
        grant(grants, CONTRACT_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_BIZ);
        // §6 使用单位档案 + §4 使用单位本单位信息读
        grant(grants, USEUNIT_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_BIZ, RoleCatalog.UNIT_ADMIN);
        grant(grants, USEUNIT_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_BIZ);

        // §6 移交单：ADMIN 只读（§12 待确认项），安装经理发起/指派，维保经理确认，质量负责人看质量节点
        grant(grants, HANDOVER_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_QUALITY);
        // §5 INSTALL_WORKER 仅现场查验/整改照片，INSTALL_CLERK 仅资料上传（端点级动作在 P3 收敛到 submit-docs）；
        // ADMIN 为平台兜底（状态推进不等于签字：节点签字角色仍由 HandoverService.NODE_ROLES 卡）
        grant(grants, HANDOVER_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.INSTALL_WORKER, RoleCatalog.MAINT_MANAGER);
        // §6 外接建档与移交单同一档权限
        grant(grants, FILING_READ,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER, RoleCatalog.MAINT_QUALITY);
        grant(grants, FILING_WRITE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN,
                RoleCatalog.INSTALL_MANAGER, RoleCatalog.INSTALL_CLERK,
                RoleCatalog.MAINT_MANAGER);

        grant(grants, REGION_WRITE, RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN);
        // §6 账号启停/重置密码：平台层全量，维保经理限本部门（部门收敛在 P3 账号模块）
        grant(grants, ACCOUNT_MANAGE,
                RoleCatalog.ADMIN, RoleCatalog.SYS_ADMIN, RoleCatalog.MAINT_MANAGER);

        for (Map.Entry<String, Set<String>> entry : grants.entrySet()) {
            requireKnownCapability(entry.getKey());
            for (String role : entry.getValue()) {
                if (!RoleCatalog.known(role)) {
                    throw new IllegalStateException("权限矩阵出现未知角色码: " + role
                            + "（能力 " + entry.getKey() + "），请同步 RoleCatalog");
                }
            }
        }
        if (!grants.keySet().equals(CAPABILITIES)) {
            throw new IllegalStateException("权限矩阵能力码与常量不一致，缺少: "
                    + CAPABILITIES.stream().filter(c -> !grants.containsKey(c)).toList());
        }
        GRANTS = Map.copyOf(grants);
    }

    private PermissionMatrix() {
    }

    /** 角色是否具备能力；未知角色一律拒绝，未知能力码抛异常（拼错不静默放行） */
    public static boolean allows(String role, String capability) {
        requireKnownCapability(capability);
        if (role == null) {
            return false;
        }
        Set<String> roles = GRANTS.get(capability);
        return roles != null && roles.contains(role);
    }

    /** 能力码拼写校验：P3 标注 {@code @RequirePerm} 时用同一个常量，写错即抛异常 */
    public static void requireKnownCapability(String capability) {
        if (capability == null || !CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("未知权限码: " + capability + "（见 PermissionMatrix 常量）");
        }
    }

    public static Set<String> capabilities() {
        return CAPABILITIES;
    }

    private static void grant(Map<String, Set<String>> grants, String capability, String... roles) {
        Set<String> holders = grants.computeIfAbsent(capability, key -> new LinkedHashSet<>());
        for (String role : roles) {
            holders.add(role);
        }
    }
}
