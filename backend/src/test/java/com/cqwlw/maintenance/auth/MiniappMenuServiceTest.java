package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.ScopeResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 小程序菜单下发（M1，规格 §3.4）：ROLE_TABS 逐字镜像蓝图 routes-and-menus.md L257–315，
 * 多角色并集去重 ≤5、workspace 切换、actions=PermissionMatrix 并集、badges 键存在。
 */
class MiniappMenuServiceTest {

    private MiniappMenuService svc() {
        FaultMapper faults = mock(FaultMapper.class);
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        when(faults.selectCount(any())).thenReturn(3L);
        when(records.selectCount(any())).thenReturn(2L);
        return new MiniappMenuService(faults, records, mock(ScopeResolver.class));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tabs(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("tabs");
    }

    private List<String> keys(Map<String, Object> out) {
        return tabs(out).stream().map(t -> String.valueOf(t.get("key"))).collect(Collectors.toList());
    }

    /** 蓝图 ROLE_TABS 逐字期望（key|text|icon|path），漂移即失败 */
    private static final Map<String, String[]> EXPECTED = Map.ofEntries(
            Map.entry("INST_CLERK", new String[]{
                    "install-home|工作台|gauge|/pages/install/home/index",
                    "handover-list|移交单|file-signature|/pkg-handover/pages/list/index",
                    "handover-docs|资料|folder|/pkg-handover/pages/list/index?tab=docs",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("INST_PM", new String[]{
                    "install-home|工作台|gauge|/pages/install/home/index",
                    "handover-list|移交单|file-signature|/pkg-handover/pages/list/index",
                    "install-projects|项目|building|/pkg-admin/pages/projects/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("INST_MGR", new String[]{
                    "install-home|工作台|gauge|/pages/install/home/index",
                    "handover-list|移交单|file-signature|/pkg-handover/pages/list/index",
                    "handover-review|审核|file-check|/pkg-handover/pages/list/index?tab=review",
                    "stats|统计|chart|/pkg-admin/pages/stats/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("MAINT_TECH", new String[]{
                    "maint-home|工作台|gauge|/pages/maint/home/index",
                    "maint-orders|工单|clipboard|/pkg-maintenance/pages/orders/index",
                    "maint-elevators|电梯|elevator|/pages/elevator/list/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("MAINT_SUP", new String[]{
                    "maint-home|工作台|gauge|/pages/maint/home/index",
                    "maint-orders|工单|clipboard|/pkg-maintenance/pages/orders/index",
                    "maint-projects|项目|building|/pkg-admin/pages/projects/index",
                    "stats|统计|chart|/pkg-admin/pages/stats/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("MAINT_MGR", new String[]{
                    "maint-home|工作台|gauge|/pages/maint/home/index",
                    "maint-projects|项目|building|/pkg-admin/pages/projects/index",
                    "maint-contracts|合同|file-text|/pkg-admin/pages/contracts/index",
                    "stats|统计|chart|/pkg-admin/pages/stats/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("CLIENT_ADMIN", new String[]{
                    "client-home|首页|home|/pages/client/home/index",
                    "client-todo|待办|file-signature|/pkg-client/pages/todo/index",
                    "client-elevators|电梯|elevator|/pages/elevator/list/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("CLIENT_SIGNER", new String[]{
                    "client-home|首页|home|/pages/client/home/index",
                    "client-todo|待办|file-signature|/pkg-client/pages/todo/index",
                    "client-elevators|电梯|elevator|/pages/elevator/list/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("PLATFORM_ADMIN", new String[]{
                    "admin-org|组织|users|/pkg-admin/pages/users/index",
                    "stats|统计|chart|/pkg-admin/pages/stats/index",
                    "admin-audit|审计|shield|/pkg-admin/pages/audit/index",
                    "admin-settings|配置|settings|/pkg-admin/pages/settings/index",
                    "profile|我的|user|/pages/profile/index"}),
            Map.entry("SYS_ADMIN", new String[]{
                    "admin-org|组织|users|/pkg-admin/pages/users/index",
                    "stats|统计|chart|/pkg-admin/pages/stats/index",
                    "admin-audit|审计|shield|/pkg-admin/pages/audit/index",
                    "admin-settings|配置|settings|/pkg-admin/pages/settings/index",
                    "profile|我的|user|/pages/profile/index"}));

    @Test
    void allTenRoleTabsMatchBlueprintVerbatim() {
        for (Map.Entry<String, String[]> e : EXPECTED.entrySet()) {
            Map<String, Object> out = svc().menusOf(Set.of(e.getKey()), null, null);
            List<Map<String, Object>> got = tabs(out);
            assertEquals(e.getValue().length, got.size(), e.getKey() + " tab 数");
            for (int i = 0; i < got.size(); i++) {
                String[] want = e.getValue()[i].split("\\|");
                assertEquals(want[0], got.get(i).get("key"), e.getKey() + " key");
                assertEquals(want[1], got.get(i).get("text"), e.getKey() + " text");
                assertEquals(want[2], got.get(i).get("icon"), e.getKey() + " icon");
                assertEquals(want[3], got.get(i).get("path"), e.getKey() + " path");
            }
        }
    }

    @Test
    void maintTechHomeAndWorkspace() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH"), null, null);
        assertEquals("/pages/maint/home/index", out.get("home"));
        assertEquals("MAINT_TECH", out.get("activeWorkspace"));
        assertEquals(List.of("maint-home", "maint-orders", "maint-elevators", "profile"), keys(out));
    }

    @Test
    void multiRoleUnionDedupAtMostFive() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH", "CLIENT_ADMIN"), null, null);
        assertTrue(tabs(out).size() <= 5);
        assertEquals("MAINT_TECH", out.get("activeWorkspace")); // ROLE_PRIORITY 维保优先
        assertEquals(List.of("maint-home", "maint-orders", "maint-elevators", "profile", "client-home"), keys(out));
    }

    @Test
    void workspaceSwitchOverridesHomeAndTabs() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH", "CLIENT_ADMIN"), "CLIENT_ADMIN", null);
        assertEquals("/pages/client/home/index", out.get("home"));
        assertEquals(List.of("client-home", "client-todo", "client-elevators", "profile"), keys(out));
    }

    @Test
    void actionsArePermissionUnion() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH"), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = (Map<String, Object>) out.get("actions");
        assertEquals(Boolean.TRUE, actions.get("order:grab"));
        assertEquals(Boolean.FALSE, actions.get("handover:create"));
        assertEquals(Boolean.TRUE, actions.get("record:sign_tech"));
    }

    @Test
    void badgesKeysPresentWithPrincipal() {
        Employee me = new Employee();
        me.id = "e1";
        me.department = "MAINT";
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH"), null, me);
        @SuppressWarnings("unchecked")
        Map<String, Object> badges = (Map<String, Object>) out.get("badges");
        assertTrue(badges.containsKey("pendingOrders"));
        assertTrue(badges.containsKey("pendingSigns"));
    }

    @Test
    void extensionRolesProjectToNearestWorkspace() {
        assertEquals(List.of("maint-home", "maint-orders", "maint-projects", "stats", "profile"),
                keys(svc().menusOf(Set.of("MAINT_QUALITY"), null, null)));
        assertEquals(List.of("install-home", "handover-list", "handover-docs", "profile"),
                keys(svc().menusOf(Set.of("INSTALLER"), null, null)));
    }

    @Test
    void badgeKeyOnOrdersTab() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH"), null, null);
        assertEquals("pendingOrders", tabs(out).get(1).get("badgeKey"));
        Map<String, Object> client = svc().menusOf(Set.of("CLIENT_ADMIN"), null, null);
        assertEquals("pendingSigns", tabs(client).get(1).get("badgeKey"));
    }

    @Test
    void unknownWorkspaceFallsBackToUnion() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_TECH"), "NOT_A_ROLE", null);
        assertEquals("/pages/maint/home/index", out.get("home"));
        assertFalse(keys(out).isEmpty());
    }

    /** 2026-11-15 裁决：SYS_ADMIN 平台域首页/5tabs；外接建档入口归维保工作台 */
    @Test
    void sysAdminPlatformHomeAndTabs() {
        Map<String, Object> out = svc().menusOf(Set.of("SYS_ADMIN"), null, null);
        assertEquals("/pkg-admin/pages/users/index", out.get("home"));
        assertEquals("SYS_ADMIN", out.get("activeWorkspace"));
        assertEquals(List.of("admin-org", "stats", "admin-audit", "admin-settings", "profile"), keys(out));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maintQuickActionsIncludeFiling() {
        Map<String, Object> out = svc().menusOf(Set.of("MAINT_MGR"), null, null);
        List<Map<String, Object>> qa = (List<Map<String, Object>>) out.get("quickActions");
        assertTrue(qa.stream().anyMatch(q -> "filing".equals(q.get("key"))
                && "/pkg-handover/pages/filing-init/index".equals(q.get("path"))));
        Map<String, Object> inst = svc().menusOf(Set.of("INST_PM"), null, null);
        List<Map<String, Object>> iqa = (List<Map<String, Object>>) inst.get("quickActions");
        assertFalse(iqa.stream().anyMatch(q -> "filing".equals(q.get("key"))));
    }
}