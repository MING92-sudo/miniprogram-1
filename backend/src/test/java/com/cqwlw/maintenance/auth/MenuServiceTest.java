package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端菜单下发（docs/14 §11 P2）：菜单树按能力码过滤，分组在无可见子项时整组消失。
 * 菜单过滤只是体验优化——端点鉴权仍由 PermissionMatrix/各自门禁判定，这里不承担鉴权职责。
 */
class MenuServiceTest {

    private final MenuService service = new MenuService();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childrenOf(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("children");
    }

    private static List<String> indexes(List<Map<String, Object>> menus) {
        return menus.stream().map(m -> String.valueOf(m.get("index"))).toList();
    }

    /** ADMIN：全部菜单，分组子项完整 */
    @Test
    void platformAdminSeesEverything() {
        List<Map<String, Object>> menus = service.menusOf(RoleCatalog.ADMIN);
        assertEquals(List.of("/dashboard", "/schedule", "/ledger", "/templates",
                "monitor", "archive", "register", "logs"), indexes(menus));
        Map<String, Object> archive = menus.stream()
                .filter(m -> "archive".equals(m.get("index"))).findFirst().orElseThrow();
        assertEquals(4, childrenOf(archive).size());
    }

    /** 班组长：能看看板/计划/台账/工单监控与人员，但没有模板、使用单位、上报日志 */
    @Test
    void leaderSeesReadOnlySubset() {
        List<Map<String, Object>> menus = service.menusOf(RoleCatalog.LEADER);
        // 上报日志按 docs/14 §4「班组长本组只读」保留（§6 该行为 — 属文档内部不一致，以 §4 为准）
        assertEquals(List.of("/dashboard", "/schedule", "/ledger", "monitor", "archive", "register", "logs"),
                indexes(menus));
        assertFalse(indexes(menus).contains("/templates"));
        Map<String, Object> monitor = menus.stream()
                .filter(m -> "monitor".equals(m.get("index"))).findFirst().orElseThrow();
        assertEquals(List.of("工单列表", "急修单", "上报异常清单"),
                childrenOf(monitor).stream().map(c -> String.valueOf(c.get("title"))).toList());
        Map<String, Object> archive = menus.stream()
                .filter(m -> "archive".equals(m.get("index"))).findFirst().orElseThrow();
        // 使用单位档案需 useunit:read，班组长没有 → 该子项被裁掉
        assertEquals(List.of("/archive/company", "/archive/employees", "/archive/elevators"),
                childrenOf(archive).stream().map(c -> String.valueOf(c.get("index"))).toList());
    }

    /** 安装文员：只有看板 + 人员档案；「档案管理」只剩人员一项，其余分组整体消失 */
    @Test
    void installClerkSeesOnlyDashboardAndEmployees() {
        List<Map<String, Object>> menus = service.menusOf(RoleCatalog.INSTALL_CLERK);
        assertEquals(List.of("/dashboard", "archive", "register"), indexes(menus));
        Map<String, Object> archive = menus.get(1);
        assertEquals(List.of("/archive/company", "/archive/employees"),
                childrenOf(archive).stream().map(c -> String.valueOf(c.get("index"))).toList());
    }

    /** 质量负责人：模板管理可见（启停），但人员档案与上报日志不可见 */
    @Test
    void qualityLeadSeesTemplateButNotEmployees() {
        List<Map<String, Object>> menus = service.menusOf(RoleCatalog.MAINT_QUALITY);
        assertTrue(indexes(menus).contains("/templates"));
        assertFalse(indexes(menus).contains("register"));
        // 上报日志在 §6 为质量负责人 R（record:read），可看不可重报（report:retry 不含它）
        assertTrue(indexes(menus).contains("logs"));
        Map<String, Object> archive = menus.stream()
                .filter(m -> "archive".equals(m.get("index"))).findFirst().orElseThrow();
        // 只保留电梯档案（ledger:read）；使用单位/人员/维保单位三项均无权
        assertEquals(List.of("/archive/elevators"),
                childrenOf(archive).stream().map(c -> String.valueOf(c.get("index"))).toList());
    }

    /** 不可登录管理端的角色：空菜单（不是 null），前端据此提示走小程序 */
    @Test
    void fieldRolesGetEmptyMenus() {
        assertTrue(service.menusOf(RoleCatalog.WORKER).isEmpty());
        assertTrue(service.menusOf(RoleCatalog.INSTALL_WORKER).isEmpty());
        assertTrue(service.menusOf(RoleCatalog.UNIT_ADMIN).isEmpty());
        assertTrue(service.menusOf(null).isEmpty());
    }
}
