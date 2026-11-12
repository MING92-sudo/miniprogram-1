package com.cqwlw.maintenance.auth;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端菜单下发（docs/14 §9.3、§11 P2）：按角色能力过滤菜单树，前端只渲染下发的项。
 * 权威仍是 {@link PermissionMatrix}——菜单过滤只是体验，后端端点各自再判一次。
 * 顺序与 `admin/src/layout/AdminLayout.vue` 的兜底表一致；前端拿到接口结果后不再自行过滤。
 */
@Service
public class MenuService {

    /** 菜单节点：叶子带 path，分组只有 children（index 为前端展开分组用的 key） */
    private record Node(String index, String title, String perm, List<Node> children) {
    }

    private static final List<Node> TREE = List.of(
            new Node("/dashboard", "监控看板", PermissionMatrix.CONSOLE_READ, null),
            new Node("/schedule", "计划调度", PermissionMatrix.PLAN_WRITE, null),
            new Node("/ledger", "电梯台账", PermissionMatrix.LEDGER_READ, null),
            new Node("/templates", "模板管理", PermissionMatrix.TEMPLATE_WRITE, null),
            new Node("monitor", "工单监控", null, List.of(
                    new Node("/orders", "工单列表", PermissionMatrix.ORDER_READ, null),
                    new Node("/faults", "急修单", PermissionMatrix.FAULT_READ, null),
                    new Node("/reports/failed", "上报异常清单", PermissionMatrix.ORDER_READ, null))),
            new Node("archive", "档案管理", null, List.of(
                    new Node("/archive/company", "维保单位", PermissionMatrix.EMPLOYEE_READ, null),
                    new Node("/archive/use-units", "使用单位", PermissionMatrix.USEUNIT_READ, null),
                    new Node("/archive/employees", "人员", PermissionMatrix.EMPLOYEE_READ, null),
                    new Node("/archive/elevators", "电梯档案", PermissionMatrix.LEDGER_READ, null))),
            new Node("register", "平台登记", null, List.of(
                    new Node("/register/workers", "人员管理", PermissionMatrix.EMPLOYEE_READ, null))),
            new Node("logs", "上报日志", null, List.of(
                    new Node("/reports/logs", "上报日志", PermissionMatrix.RECORD_READ, null))));

    /** 角色可用的菜单树；不可登录管理端的角色返回空列表（前端据此提示走小程序） */
    public List<Map<String, Object>> menusOf(String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!RoleCatalog.canUseAdminConsole(role)) {
            return out;
        }
        for (Node node : TREE) {
            Map<String, Object> view = render(node, role);
            if (view != null) {
                out.add(view);
            }
        }
        return out;
    }

    /** 分组：至少一个子项可见才保留，并整体裁掉不可见子项 */
    private Map<String, Object> render(Node node, String role) {
        if (node.children() == null) {
            return PermissionMatrix.allows(role, node.perm()) ? leaf(node) : null;
        }
        List<Map<String, Object>> kids = new ArrayList<>();
        for (Node child : node.children()) {
            Map<String, Object> view = render(child, role);
            if (view != null) {
                kids.add(view);
            }
        }
        if (kids.isEmpty()) {
            return null;
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("index", node.index());
        group.put("title", node.title());
        group.put("children", kids);
        return group;
    }

    private static Map<String, Object> leaf(Node node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", node.index());
        m.put("title", node.title());
        m.put("perm", node.perm());
        return m;
    }
}
