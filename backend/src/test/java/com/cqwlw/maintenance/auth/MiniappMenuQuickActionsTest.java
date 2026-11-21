package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
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
 * P1（2026-11-16 审查）：快捷动作必须与路由白名单/权限矩阵同口径（权限面=渲染面）：
 * CLIENT 不得下发维保子包页面；MAINT_TECH 不得下发 filing/plan；
 * plan 入口下发给 MAINT_SUP/MAINT_MGR；扫码开工深链未实现前不得下发 mode=scan（docs/12 C-17）。
 */
class MiniappMenuQuickActionsTest {

    private MiniappMenuService svc() {
        FaultMapper faults = mock(FaultMapper.class);
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        when(faults.selectCount(any())).thenReturn(0L);
        when(records.selectCount(any())).thenReturn(0L);
        return new MiniappMenuService(faults, records, mock(SysUserRoleMapper.class), mock(ElevatorMapper.class));
    }

    @SuppressWarnings("unchecked")
    private List<String> qaPaths(Set<String> roles) {
        Map<String, Object> out = svc().menusOf(roles, null, null);
        return ((List<Map<String, Object>>) out.get("quickActions")).stream()
                .map(q -> String.valueOf(q.get("path"))).collect(Collectors.toList());
    }

    @Test
    void clientQuickActionsStayInClientScope() {
        List<String> paths = qaPaths(Set.of("CLIENT_ADMIN"));
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("/pkg-maintenance/")),
                "CLIENT 快捷动作不得指向维保子包（守卫必 403）");
        assertTrue(paths.contains("/pkg-client/pages/todo/index"), "CLIENT 待办签字入口保留");
    }

    /** P1 T6：客户工作台收敛——quickActions 删 report/emergency（保留 elevators/todo）；actions 不下发 client:evaluate/client:report */
    @Test
    @SuppressWarnings("unchecked")
    void clientQuickActionsExcludeReportAndEvaluate() {
        for (String role : List.of("CLIENT_ADMIN", "CLIENT_SIGNER")) {
            Map<String, Object> out = svc().menusOf(Set.of(role), null, null);
            List<Map<String, Object>> qa = (List<Map<String, Object>>) out.get("quickActions");
            assertTrue(qa.stream().noneMatch(a -> "report".equals(a.get("key")) || "emergency".equals(a.get("key"))),
                    role + " 快捷动作下线一键报修/急修进度（T2 矩阵 client:report DENY，docs/14 §10.3）");
            assertTrue(qa.stream().anyMatch(a -> "todo".equals(a.get("key"))), role + " 待办签字保留");
            assertTrue(qa.stream().anyMatch(a -> "elevators".equals(a.get("key"))), role + " 我的电梯保留");
            assertTrue(qa.stream().noneMatch(a -> String.valueOf(a.get("path")).startsWith("/pkg-maintenance/")),
                    role + " 不得指向维保子包");
            Map<String, Object> actions = (Map<String, Object>) out.get("actions");
            assertFalse(actions.containsKey("client:evaluate"), role + " BUTTON_CODES 删 client:evaluate（评价入口下线）");
            assertFalse(actions.containsKey("client:report"), role + " actions 不下发 client:report");
            assertEquals(Boolean.TRUE, actions.get("client:sign"), role + " client:sign 保留且为 Y（T2 最小集）");
        }
    }

    @Test
    void maintTechExcludesFilingAndPlan() {
        List<String> paths = qaPaths(Set.of("MAINT_TECH"));
        assertTrue(paths.stream().noneMatch(p -> p.contains("filing-init")), "TECH 无 filing:write");
        assertTrue(paths.stream().noneMatch(p -> p.contains("/plan/index")), "TECH 无 plan:view");
    }

    @Test
    void maintSupIncludesFilingAndPlan() {
        List<String> paths = qaPaths(Set.of("MAINT_SUP"));
        assertTrue(paths.stream().anyMatch(p -> p.contains("filing-init")));
        assertTrue(paths.stream().anyMatch(p -> p.contains("/plan/index")));
    }

    @Test
    void maintMgrIncludesPlan() {
        assertTrue(qaPaths(Set.of("MAINT_MGR")).stream().anyMatch(p -> p.contains("/plan/index")));
    }

    @Test
    void instQaMatchesRouteRoles() {
        // 2026-11-21 裁决：handover:create 扩 INST_CLERK——create/upload 均 INST_CLERK/INST_PM
        List<String> clerk = qaPaths(Set.of("INST_CLERK"));
        assertTrue(clerk.stream().anyMatch(p -> p.contains("/create/index")), "CLERK 有 handover:create");
        assertTrue(clerk.stream().anyMatch(p -> p.contains("/upload/index")));
        List<String> mgr = qaPaths(Set.of("INST_MGR"));
        assertTrue(mgr.stream().noneMatch(p -> p.contains("/create/index") || p.contains("/upload/index")),
                "MGR 无 create/upload 路由角色");
        List<String> pm = qaPaths(Set.of("INST_PM"));
        assertTrue(pm.stream().anyMatch(p -> p.contains("/create/index")));
        assertTrue(pm.stream().anyMatch(p -> p.contains("/upload/index")));
    }

    @Test
    void noUnimplementedScanDeepLink() {
        assertTrue(qaPaths(Set.of("MAINT_TECH")).stream().noneMatch(p -> p.contains("mode=scan")),
                "扫码开工深链未实现（docs/12 C-17），不得下发");
    }
}