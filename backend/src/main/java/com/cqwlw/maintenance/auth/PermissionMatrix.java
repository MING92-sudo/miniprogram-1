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

    /** 三态：Y 可操作 / C 只读或需审批 / DENY 禁止（spec 的 '-'）。 */
    public enum TriState { Y, C, DENY }

    // ==== 86 新码常量（71 base + 15 extension）====
    public static final String HANDOVER_VIEW = "handover:view";
    public static final String HANDOVER_CREATE = "handover:create";
    public static final String HANDOVER_EDIT = "handover:edit";
    public static final String HANDOVER_DOC_UPLOAD = "handover:doc_upload";
    public static final String HANDOVER_DOC_DELETE = "handover:doc_delete";
    public static final String HANDOVER_SUBMIT = "handover:submit";
    public static final String HANDOVER_REVIEW = "handover:review";
    public static final String HANDOVER_REVIEW_RETURN = "handover:review_return";
    public static final String HANDOVER_RECEIVE = "handover:receive";
    public static final String HANDOVER_INSPECT = "handover:inspect";
    public static final String HANDOVER_RECTIFY = "handover:rectify";
    public static final String HANDOVER_SIGN_INSTALL = "handover:sign_install";
    public static final String HANDOVER_SIGN_MAINT = "handover:sign_maint";
    public static final String HANDOVER_VOID = "handover:void";
    public static final String HANDOVER_EXPORT = "handover:export";
    public static final String HANDOVER_NOTIFY_CLIENT = "handover:notify_client";
    public static final String PROJECT_VIEW = "project:view";
    public static final String PROJECT_EDIT = "project:edit";
    public static final String CONTRACT_VIEW = "contract:view";
    public static final String CONTRACT_EDIT = "contract:edit";
    public static final String ELEVATOR_VIEW = "elevator:view";
    public static final String ELEVATOR_EDIT = "elevator:edit";
    public static final String ELEVATOR_QR = "elevator:qr";
    public static final String ELEVATOR_TRANSFER = "elevator:transfer";
    public static final String ELEVATOR_EXPORT = "elevator:export";
    public static final String PLAN_VIEW = "plan:view";
    public static final String PLAN_GENERATE = "plan:generate";
    public static final String TASK_ACCEPT = "task:accept";
    public static final String TASK_ASSIGN_PARTNER = "task:assign_partner";
    public static final String CHECKLIST_FILL = "checklist:fill";
    public static final String RECORD_VIEW = "record:view";
    public static final String RECORD_SUBMIT = "record:submit";
    public static final String RECORD_SIGN_TECH = "record:sign_tech";
    public static final String RECORD_REVIEW = "record:review";
    public static final String RECORD_SIGN_REQUEST = "record:sign_request";
    public static final String RECORD_SIGN = "record:sign";
    public static final String RECORD_REFUSE = "record:refuse";
    public static final String RECORD_ARCHIVE = "record:archive";
    public static final String RECORD_AMEND = "record:amend";
    public static final String RECORD_EXPORT = "record:export";
    public static final String ORDER_CREATE = "order:create";
    public static final String ORDER_CREATE_PROXY = "order:create_proxy";
    public static final String ORDER_VIEW_POOL = "order:view_pool";
    public static final String ORDER_VIEW_OWN = "order:view_own";
    public static final String ORDER_GRAB = "order:grab";
    public static final String ORDER_DISPATCH = "order:dispatch";
    public static final String ORDER_ARRIVE = "order:arrive";
    public static final String ORDER_PROCESS = "order:process";
    public static final String ORDER_SUBMIT = "order:submit";
    public static final String ORDER_CONFIRM = "order:confirm";
    public static final String ORDER_CLOSE = "order:close";
    public static final String ORDER_CANCEL = "order:cancel";
    public static final String ORDER_ESCALATE = "order:escalate";
    public static final String ORDER_EXPORT = "order:export";
    public static final String CLIENT_INVITE = "client:invite";
    public static final String CLIENT_USER_MANAGE = "client:user_manage";
    public static final String CLIENT_ELEVATOR_VIEW = "client:elevator_view";
    public static final String CLIENT_REPORT = "client:report";
    public static final String CLIENT_SIGN = "client:sign";
    public static final String CLIENT_EVALUATE = "client:evaluate";
    public static final String CLIENT_DOWNLOAD = "client:download";
    public static final String USER_VIEW = "user:view";
    public static final String USER_CREATE = "user:create";
    public static final String USER_DISABLE = "user:disable";
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String INVITE_MANAGE = "invite:manage";
    public static final String STATS_VIEW = "stats:view";
    public static final String STATS_EXPORT = "stats:export";
    public static final String AUDIT_VIEW = "audit:view";
    public static final String CONFIG_MANAGE = "config:manage";
    public static final String DATA_EXPORT_SENSITIVE = "data:export_sensitive";
    // 15 扩展码（M0 §2.2）
    public static final String REPORT_VIEW = "report:view";
    public static final String REPORT_RETRY_NEW = "report:retry";
    public static final String FILING_VIEW = "filing:view";
    public static final String FILING_WRITE_NEW = "filing:write";
    public static final String REGION_VIEW = "region:view";
    public static final String REGION_WRITE_NEW = "region:write";
    public static final String TEMPLATE_VIEW = "template:view";
    public static final String TEMPLATE_WRITE_NEW = "template:write";
    public static final String TEMPLATE_TOGGLE = "template:toggle";
    public static final String PLATFORM_WORKER_VIEW = "platform:worker_view";
    public static final String PLATFORM_WORKER_REGISTER = "platform:worker_register";
    public static final String PLATFORM_SYNC = "platform:sync";
    public static final String RECORD_SPOTCHECK = "record:spotcheck";
    public static final String CLIENT_EDIT = "client:edit";
    public static final String USER_RESET_PASSWORD = "user:reset_password";

    private static final java.util.Map<String, java.util.Map<String, TriState>> STATES = new java.util.HashMap<>();
    private static final java.util.Set<String> CANONICAL_CAPS = new java.util.LinkedHashSet<>();

    private static void g(String code, TriState st, String... roles) {
        CANONICAL_CAPS.add(code);
        java.util.Map<String, TriState> row = STATES.computeIfAbsent(code, k -> new java.util.HashMap<>());
        for (String r : roles) {
            row.put(r, st);
        }
    }
    private static void y(String code, String... roles) { g(code, TriState.Y, roles); }
    private static void c(String code, String... roles) { g(code, TriState.C, roles); }

    static {
        String PA = "PLATFORM_ADMIN", SA = "SYS_ADMIN", IM = "INST_MGR", IP = "INST_PM", IC = "INST_CLERK",
                IW = "INSTALLER", MM = "MAINT_MGR", MS = "MAINT_SUP", MT = "MAINT_TECH",
                MB = "MAINT_BIZ", MQ = "MAINT_QUALITY", CA = "CLIENT_ADMIN", CS = "CLIENT_SIGNER";
        // §4.1 安装移交（16）
        y(HANDOVER_VIEW, IM, MS, MM, PA, SA); c(HANDOVER_VIEW, IC, IP, CA);
        y(HANDOVER_CREATE, IP);
        y(HANDOVER_EDIT, IP); c(HANDOVER_EDIT, IC);
        y(HANDOVER_DOC_UPLOAD, IC, IP);
        y(HANDOVER_DOC_DELETE, IC, IP);
        y(HANDOVER_SUBMIT, IP);
        y(HANDOVER_REVIEW, IM);
        y(HANDOVER_REVIEW_RETURN, IM);
        y(HANDOVER_RECEIVE, MS);
        y(HANDOVER_INSPECT, IW); c(HANDOVER_INSPECT, MT);
        y(HANDOVER_RECTIFY, IP, IC, IW);
        y(HANDOVER_SIGN_INSTALL, IM, IW);
        y(HANDOVER_SIGN_MAINT, MS, MM);
        y(HANDOVER_VOID, IM, MM, PA, SA);
        y(HANDOVER_EXPORT, IM, MS, MM, PA, SA); c(HANDOVER_EXPORT, CA);
        y(HANDOVER_NOTIFY_CLIENT, MS);
        // §4.2 项目合同电梯（9）
        y(PROJECT_VIEW, MS, MM, PA, SA); c(PROJECT_VIEW, IC, IP, IM, CA);
        y(PROJECT_EDIT, MM, PA, SA);
        y(CONTRACT_VIEW, MM, PA, SA, MB); c(CONTRACT_VIEW, MS, CA);
        y(CONTRACT_EDIT, MM, PA, SA, MB);
        c(ELEVATOR_VIEW, PA, SA, IM, IP, IC, IW, MM, MS, MT, MB, MQ, CA, CS);
        y(ELEVATOR_EDIT, MS, PA, SA);
        y(ELEVATOR_QR, MT, MS, CA);
        y(ELEVATOR_TRANSFER, MM, PA, SA);
        y(ELEVATOR_EXPORT, MM, PA, SA); c(ELEVATOR_EXPORT, MS, CA);
        // §4.3 维保作业（15）
        y(PLAN_VIEW, MS, MM); c(PLAN_VIEW, MT, CA);
        y(PLAN_GENERATE, MS, MM);
        y(TASK_ACCEPT, MT);
        y(TASK_ASSIGN_PARTNER, MS, MT);
        y(CHECKLIST_FILL, MT);
        y(RECORD_VIEW, MS, MM, PA, SA); c(RECORD_VIEW, MT, CS);
        y(RECORD_SUBMIT, MT);
        y(RECORD_SIGN_TECH, MT);
        y(RECORD_REVIEW, MS, MM);
        y(RECORD_SIGN_REQUEST, MT, MS);
        y(RECORD_SIGN, CS);
        y(RECORD_REFUSE, CS);
        y(RECORD_ARCHIVE, MS);
        y(RECORD_AMEND, MM, PA, SA);
        y(RECORD_EXPORT, MS, MM, PA, SA); c(RECORD_EXPORT, CA);
        // §4.4 急修报修（14）
        y(ORDER_CREATE, CA, CS);
        y(ORDER_CREATE_PROXY, MS);
        y(ORDER_VIEW_POOL, MS, MM); c(ORDER_VIEW_POOL, MT);
        y(ORDER_VIEW_OWN, CA, CS);
        y(ORDER_GRAB, MT);
        y(ORDER_DISPATCH, MS, MM);
        c(ORDER_ARRIVE, MT);
        c(ORDER_PROCESS, MT);
        y(ORDER_SUBMIT, MT);
        y(ORDER_CONFIRM, CS);
        y(ORDER_CLOSE, MS);
        y(ORDER_CANCEL, CA, MS);
        y(ORDER_ESCALATE, MM);
        y(ORDER_EXPORT, MS, MM, PA, SA); c(ORDER_EXPORT, CA);
        // §4.5 使用单位（7）
        y(CLIENT_INVITE, CA, MS);
        y(CLIENT_USER_MANAGE, CA);
        y(CLIENT_ELEVATOR_VIEW, CA, CS);
        y(CLIENT_REPORT, CA, CS);
        y(CLIENT_SIGN, CS);
        y(CLIENT_EVALUATE, CA, CS);
        y(CLIENT_DOWNLOAD, CA, CS);
        // §4.6 组织权限审计（10）
        y(USER_VIEW, PA, SA); c(USER_VIEW, IM, MS, MM, CA);
        y(USER_CREATE, IM, MS, MM, CA, PA, SA);
        y(USER_DISABLE, IM, MS, MM, CA, PA, SA);
        y(ROLE_ASSIGN, PA, SA); c(ROLE_ASSIGN, CA);
        y(INVITE_MANAGE, IM, MS, MM, CA, PA, SA);
        y(STATS_VIEW, IM, MS, MM, CA, PA, SA);
        y(STATS_EXPORT, MM, PA, SA); c(STATS_EXPORT, IM, MS, CA);
        y(AUDIT_VIEW, PA, SA); c(AUDIT_VIEW, CA);
        y(CONFIG_MANAGE, PA, SA);
        y(DATA_EXPORT_SENSITIVE, PA, SA); // 审批工作流 M5；M1 只编码平台持有该能力
        // M0 §2.2 扩展（15）
        y(REPORT_VIEW, PA, SA, MM); c(REPORT_VIEW, MS, MQ);
        y(REPORT_RETRY_NEW, PA, SA, MM);
        y(FILING_VIEW, PA, SA, IP, IC, MM); c(FILING_VIEW, MQ, IM);
        y(FILING_WRITE_NEW, PA, SA, IP, IC, MM);
        y(REGION_VIEW, PA, SA);
        y(REGION_WRITE_NEW, PA, SA);
        y(TEMPLATE_VIEW, PA, SA, MM); c(TEMPLATE_VIEW, MQ);
        y(TEMPLATE_WRITE_NEW, PA, SA, MM, MQ);
        y(TEMPLATE_TOGGLE, PA, SA, MQ);
        y(PLATFORM_WORKER_VIEW, PA, SA); c(PLATFORM_WORKER_VIEW, IP, IC, MM, MS);
        y(PLATFORM_WORKER_REGISTER, PA, SA, MM);
        y(PLATFORM_SYNC, PA, SA);
        y(RECORD_SPOTCHECK, MQ);
        y(CLIENT_EDIT, PA, MB);
        y(USER_RESET_PASSWORD, PA, SA, MM, IM);

        // fail-closed 一致性校验：码集==常量集（86），每码至少一个持有者
        if (CANONICAL_CAPS.size() != 86) {
            throw new IllegalStateException("三态矩阵码数异常: " + CANONICAL_CAPS.size());
        }
        for (String code : CANONICAL_CAPS) {
            java.util.Map<String, TriState> row = STATES.get(code);
            boolean hasHolder = row.values().stream().anyMatch(s -> s == TriState.Y || s == TriState.C);
            if (!hasHolder) {
                throw new IllegalStateException("三态码无持有者: " + code);
            }
        }
    }

    /** 规范角色×新码三态；未知角色→DENY，未知码→IllegalArgumentException（fail-closed）。 */
    public static TriState state(String role, String code) {
        if (code == null || !CANONICAL_CAPS.contains(code)) {
            throw new IllegalArgumentException("未知权限码: " + code);
        }
        String canonical = RoleCatalog.canonical(role);
        if (canonical == null) {
            return TriState.DENY;
        }
        return STATES.get(code).getOrDefault(canonical, TriState.DENY);
    }

    /** 新细粒度入口：要求 Y（C 不算放行；@RequirePerm(新码) M3+ 才标注）。 */
    public static boolean allowsNew(String role, String code) {
        return state(role, code) == TriState.Y;
    }

    /** 86 新码集合。 */
    public static java.util.Set<String> canonicalCapabilities() {
        return java.util.Collections.unmodifiableSet(CANONICAL_CAPS);
    }
}
