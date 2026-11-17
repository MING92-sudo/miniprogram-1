package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.util.RecordVersionUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小程序菜单下发（M1，规格 §3.4）：ROLE_TABS 逐字镜像蓝图 routes-and-menus.md L257–315。
 * 后端是唯一事实来源；前端 config/menus.js 仅兜底（M2 镜像 + 漂移守卫）。
 * 多角色按 ROLE_PRIORITY 取并集、按 key 去重、最多 5 项；workspace 切换以该角色表为准。
 */
@Service
public class MiniappMenuService {

    public static final List<String> ROLE_PRIORITY = List.of(
            "MAINT_MGR", "MAINT_SUP", "MAINT_TECH",
            "INST_MGR", "INST_PM", "INST_CLERK",
            "CLIENT_ADMIN", "CLIENT_SIGNER", "PLATFORM_ADMIN", "SYS_ADMIN");

    private static final Map<String, String> ROLE_HOME = Map.of(
            "INST_CLERK", "/pages/install/home/index",
            "INST_PM", "/pages/install/home/index",
            "INST_MGR", "/pages/install/home/index",
            "MAINT_TECH", "/pages/maint/home/index",
            "MAINT_SUP", "/pages/maint/home/index",
            "MAINT_MGR", "/pages/maint/home/index",
            "CLIENT_ADMIN", "/pages/client/home/index",
            "CLIENT_SIGNER", "/pages/client/home/index",
            "PLATFORM_ADMIN", "/pkg-admin/pages/users/index",
            "SYS_ADMIN", "/pkg-admin/pages/users/index");

    /** 扩展角色归属最近工作台（规格 §3.6）：只读投影，写按钮由 actions 隐藏 */
    private static final Map<String, String> TABS_ALIAS = Map.of(
            "MAINT_QUALITY", "MAINT_SUP",
            "MAINT_BIZ", "MAINT_SUP",
            "INSTALLER", "INST_CLERK");

    // P1 T6：client:evaluate 移除（评价入口下线，T2 矩阵对客户 DENY）；client:sign 保留
    private static final List<String> BUTTON_CODES = List.of(
            "order:grab", "order:arrive", "order:submit", "order:confirm", "order:cancel",
            "record:sign_tech", "record:sign_request", "record:review", "record:export",
            "handover:create", "handover:inspect", "handover:rectify", "handover:review",
            "client:sign", "project:edit", "elevator:export",
            "checklist:fill", "task:accept");

    private static final Map<String, List<Map<String, Object>>> ROLE_TABS = buildTabs();

    private final FaultMapper faultMapper;
    private final MaintainRecordMapper recordMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final ElevatorMapper elevatorMapper;

    public MiniappMenuService(FaultMapper faultMapper, MaintainRecordMapper recordMapper,
                              SysUserRoleMapper userRoleMapper, ElevatorMapper elevatorMapper) {
        this.faultMapper = faultMapper;
        this.recordMapper = recordMapper;
        this.userRoleMapper = userRoleMapper;
        this.elevatorMapper = elevatorMapper;
    }

    public Map<String, Object> menusOf(Set<String> roles, String workspace, Employee me) {
        Set<String> effective = workspace != null && roles.contains(workspace)
                ? Set.of(workspace) : roles;
        String ws = activeWorkspace(effective);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> user = new LinkedHashMap<>();
        if (me != null) {
            user.put("id", me.id);
            user.put("name", me.name);
        }
        user.put("roles", new ArrayList<>(effective));
        user.put("activeWorkspace", ws);
        out.put("user", user);
        out.put("home", resolveHome(effective));
        out.put("activeWorkspace", ws);
        out.put("tabs", resolveTabs(effective));
        out.put("quickActions", quickActions(ws, effective));
        Map<String, Object> actions = new LinkedHashMap<>();
        for (String code : BUTTON_CODES) {
            actions.put(code, effective.stream().anyMatch(r -> PermissionMatrix.allowsNew(r, code)));
        }
        out.put("actions", actions);
        out.put("badges", badges(effective, me));
        return out;
    }

    public static String resolveHome(Set<String> roles) {
        for (String r : ROLE_PRIORITY) {
            if (roles.contains(r)) {
                return ROLE_HOME.get(r);
            }
        }
        return "/pages/profile/index";
    }

    public static String activeWorkspace(Set<String> roles) {
        for (String r : ROLE_PRIORITY) {
            if (roles.contains(r)) {
                return r;
            }
        }
        for (String r : roles) {
            String alias = TABS_ALIAS.get(r);
            if (alias != null) {
                return alias;
            }
        }
        return null;
    }

    private List<Map<String, Object>> resolveTabs(Set<String> roles) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (String r : ROLE_PRIORITY) {
            if (!roles.contains(r)) {
                continue;
            }
            for (Map<String, Object> t : ROLE_TABS.get(r)) {
                if (seen.add(String.valueOf(t.get("key")))) {
                    tabs.add(t);
                }
            }
        }
        for (String r : roles) {
            String alias = TABS_ALIAS.get(r);
            if (alias == null) {
                continue;
            }
            for (Map<String, Object> t : ROLE_TABS.get(alias)) {
                if (seen.add(String.valueOf(t.get("key")))) {
                    tabs.add(t);
                }
            }
        }
        return tabs.size() > 5 ? tabs.subList(0, 5) : tabs;
    }

    /**
     * 快捷动作按工作区 + 角色集下发（2026-11-16 审查修复：权限面=渲染面，逐条对齐 config/routes.js 角色白名单）：
     * plan 仅 MAINT_SUP/MAINT_MGR；filing 仅 MAINT_FILING 口径；create 仅 INST_PM；upload 仅 INST_CLERK/INST_PM；
     * CLIENT 不得指向维保子包；扫码开工深链未实现（docs/12 C-17）暂不下发。
     */
    private List<Map<String, Object>> quickActions(String ws, Set<String> roles) {
        if (ws == null) {
            return List.of();
        }
        if (ws.startsWith("MAINT")) {
            List<Map<String, Object>> list = new ArrayList<>(List.of(
                    qa("emergency", "急修单", "bolt", "/pages/order/list/index?state=urgent"),
                    qa("report", "报修接单", "wrench", "/pages/order/list/index?state=pending"),
                    qa("handover", "移交验收", "file-signature", "/pkg-handover/pages/list/index")));
            if (roles.contains("MAINT_SUP") || roles.contains("MAINT_MGR")) {
                list.add(qa("plan", "维保计划", "calendar-check-r", "/pkg-maintenance/pages/plan/index"));
            }
            if (roles.contains("MAINT_SUP") || roles.contains("MAINT_MGR") || roles.contains("MAINT_BIZ")
                    || roles.contains("PLATFORM_ADMIN") || roles.contains("SYS_ADMIN")) {
                list.add(qa("filing", "外接建档", "folder", "/pkg-handover/pages/filing-init/index"));
            }
            return list;
        }
        if (ws.startsWith("CLIENT")) {
            // P1 T6（docs/14 §5.5/§10.3）：客户工作台收敛——下线一键报修/急修进度入口
            // （client:report/evaluate 矩阵默认 DENY，重开由后续裁决），仅下发我的电梯+待办签字
            return List.of(
                    qa("elevators", "我的电梯", "elevator", "/pages/elevator/list/index"),
                    qa("todo", "待办签字", "file-signature", "/pkg-client/pages/todo/index"));
        }
        if (ws.startsWith("INST")) {
            List<Map<String, Object>> list = new ArrayList<>();
            if (roles.contains("INST_PM")) {
                list.add(qa("create", "发起移交", "file-signature", "/pkg-handover/pages/create/index"));
            }
            if (roles.contains("INST_CLERK") || roles.contains("INST_PM")) {
                list.add(qa("upload", "资料上传", "folder", "/pkg-handover/pages/upload/index"));
            }
            return list;
        }
        return List.of();
    }

    private Map<String, Object> badges(Set<String> roles, Employee me) {
        long pendingOrders = 0;
        long pendingSigns = 0;
        if (me != null) {
            if (roles.stream().anyMatch(r -> r.startsWith("MAINT"))) {
                pendingOrders = faultMapper.selectCount(new LambdaQueryWrapper<Fault>()
                        .eq(Fault::getStatus, "OPEN"));
            }
            // P1（T5）：客户分支判定用 principalTypeOf（含 UNIT_ADMIN 旧码），不用 startsWith 字面量
            if (roles.stream().anyMatch(r -> RoleCatalog.principalTypeOf(r) == RoleCatalog.PrincipalType.CUSTOMER)) {
                pendingSigns = clientPendingSigns(me);
            }
        }
        return Map.of("pendingOrders", pendingOrders, "pendingSigns", pendingSigns);
    }

    /**
     * 客户待签角标（P1 T5，修复跨单位数量泄漏 + 与 T4 口径不一致）：
     * ① 本单位锚点 = sys_user_role.org_id 的 CUSTOMER 角色行（V41 并号时写入，与
     *   UnitPortalController.unitId 同口径）；无锚点 fail-closed 计 0，绝不回退全表 count；
     * ② 计数口径对齐 T4 可签集合（docs/14 §4.6，与 UnitRecordService.pendingAs 同谓词）：
     *   lifecycleStatus=PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING + 链末端最新版本 + 本单位
     *   （记录归属按电梯 useUnitId 判定，同 UnitRecordService.unitOf）；
     *   不再计 PENDING_CLIENT_SIGN（双签未齐，T4 已从客户待签列表剔除）。
     */
    private long clientPendingSigns(Employee me) {
        String unitId = clientUnitAnchor(me);
        if (unitId == null) {
            return 0;
        }
        Set<String> codes = ownElevatorCodes(unitId);
        if (codes.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>();
        for (MaintainRecord r : recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .in(MaintainRecord::getElevatorCode, codes)
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE"))) {
            // 内存双保险：与 SQL 同谓词（UnitRecordService/UnitPortalService 既有约定，防将来改查询漏条件）
            if (!"PENDING".equals(r.confirmStatus) || !"PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus)
                    || !codes.contains(r.elevatorCode)) {
                continue;
            }
            ids.add(r.id);
        }
        // P2 T5（N+1 消除）：链末端最新版本改用共享 supersededIds 批量判定——一次 IN 查询
        // 替代逐记录 selectCount，计数口径与旧实现逐集合相等（docs/14 §4.6 规则 1）
        Set<String> superseded = RecordVersionUtil.supersededIds(recordMapper, ids);
        long count = 0;
        for (String id : ids) {
            if (!superseded.contains(id)) {
                count++; // §4.6 规则 1：链末端最新版本
            }
        }
        return count;
    }

    /** 客户单位锚点：sys_user_role（EMPLOYEE 行）中 CUSTOMER 角色的 org_id；无锚点返回 null（fail-closed） */
    private String clientUnitAnchor(Employee me) {
        if (me.id == null) {
            return null;
        }
        for (SysUserRole ur : userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, me.id)
                .eq(SysUserRole::getUserType, "EMPLOYEE"))) {
            if (RoleCatalog.principalTypeOf(ur.roleCode) == RoleCatalog.PrincipalType.CUSTOMER
                    && ur.orgId != null && !ur.orgId.isBlank()) {
                return ur.orgId;
            }
        }
        return null;
    }

    /** 本单位电梯编码集（记录归属按电梯 useUnitId 收敛，与 UnitRecordService.unitOf 同口径） */
    private Set<String> ownElevatorCodes(String unitId) {
        Set<String> codes = new LinkedHashSet<>();
        for (Elevator el : elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getUseUnitId, unitId))) {
            if (el.elevatorCode != null && !el.elevatorCode.isBlank()) {
                codes.add(el.elevatorCode);
            }
        }
        return codes;
    }

    private static Map<String, Object> qa(String key, String text, String icon, String path) {
        return Map.of("key", key, "text", text, "icon", icon, "path", path);
    }

    private static Map<String, Object> tab(String key, String text, String icon, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("text", text);
        m.put("icon", icon);
        m.put("path", path);
        return m;
    }

    private static Map<String, Object> tab(String key, String text, String icon, String path, String badgeKey) {
        Map<String, Object> m = tab(key, text, icon, path);
        m.put("badgeKey", badgeKey);
        return m;
    }

    private static Map<String, List<Map<String, Object>>> buildTabs() {
        Map<String, List<Map<String, Object>>> t = new LinkedHashMap<>();
        t.put("INST_CLERK", List.of(
                tab("install-home", "工作台", "gauge", "/pages/install/home/index"),
                tab("handover-list", "移交单", "file-signature", "/pkg-handover/pages/list/index"),
                tab("handover-docs", "资料", "folder", "/pkg-handover/pages/list/index?tab=docs"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("INST_PM", List.of(
                tab("install-home", "工作台", "gauge", "/pages/install/home/index"),
                tab("handover-list", "移交单", "file-signature", "/pkg-handover/pages/list/index"),
                tab("install-projects", "项目", "building", "/pkg-admin/pages/projects/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("INST_MGR", List.of(
                tab("install-home", "工作台", "gauge", "/pages/install/home/index"),
                tab("handover-list", "移交单", "file-signature", "/pkg-handover/pages/list/index"),
                tab("handover-review", "审核", "file-check", "/pkg-handover/pages/list/index?tab=review"),
                tab("stats", "统计", "chart", "/pkg-admin/pages/stats/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("MAINT_TECH", List.of(
                tab("maint-home", "工作台", "gauge", "/pages/maint/home/index"),
                tab("maint-orders", "工单", "clipboard", "/pkg-maintenance/pages/orders/index", "pendingOrders"),
                tab("maint-elevators", "电梯", "elevator", "/pages/elevator/list/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("MAINT_SUP", List.of(
                tab("maint-home", "工作台", "gauge", "/pages/maint/home/index"),
                tab("maint-orders", "工单", "clipboard", "/pkg-maintenance/pages/orders/index", "pendingOrders"),
                tab("maint-projects", "项目", "building", "/pkg-admin/pages/projects/index"),
                tab("stats", "统计", "chart", "/pkg-admin/pages/stats/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("MAINT_MGR", List.of(
                tab("maint-home", "工作台", "gauge", "/pages/maint/home/index"),
                tab("maint-projects", "项目", "building", "/pkg-admin/pages/projects/index"),
                tab("maint-contracts", "合同", "file-text", "/pkg-admin/pages/contracts/index"),
                tab("stats", "统计", "chart", "/pkg-admin/pages/stats/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        List<Map<String, Object>> client = List.of(
                tab("client-home", "首页", "home", "/pages/client/home/index"),
                tab("client-todo", "待办", "file-signature", "/pkg-client/pages/todo/index", "pendingSigns"),
                tab("client-elevators", "电梯", "elevator", "/pages/elevator/list/index"),
                tab("profile", "我的", "user", "/pages/profile/index"));
        t.put("CLIENT_ADMIN", client);
        t.put("CLIENT_SIGNER", client);
        t.put("PLATFORM_ADMIN", List.of(
                tab("admin-org", "组织", "users", "/pkg-admin/pages/users/index"),
                tab("stats", "统计", "chart", "/pkg-admin/pages/stats/index"),
                tab("admin-audit", "审计", "shield", "/pkg-admin/pages/audit/index"),
                tab("admin-settings", "配置", "settings", "/pkg-admin/pages/settings/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        t.put("SYS_ADMIN", List.of(
                tab("admin-org", "组织", "users", "/pkg-admin/pages/users/index"),
                tab("stats", "统计", "chart", "/pkg-admin/pages/stats/index"),
                tab("admin-audit", "审计", "shield", "/pkg-admin/pages/audit/index"),
                tab("admin-settings", "配置", "settings", "/pkg-admin/pages/settings/index"),
                tab("profile", "我的", "user", "/pages/profile/index")));
        return t;
    }
}