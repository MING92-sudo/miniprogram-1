package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.service.MigrationIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 迁移核对清单管理端点（权限 P2 T7）：
 * GET /admin/migration-issues 门控 requireRead(audit:view)，
 * POST /admin/migration-issues/{id}/resolve 门控 requireWrite(config:manage)。
 * 三态矩阵实测 audit:view / config:manage 仅 PA/SA 为 Y（冻结，不扩）；
 * controller 直接 mock service，门控用真实 MultiRoleGate + MockHttpServletRequest 的 ATTR_ROLES 规范码集。
 */
class AdminMigrationIssueControllerTest {

    /** 矩阵持有者：PA=PLATFORM_ADMIN（audit:view/config:manage 均 Y） */
    private static final Set<String> PRIVILEGED = Set.of("PLATFORM_ADMIN");
    /** 无权限角色：维保经理 MAINT_MGR 对两码均 DENY（R1 冻结，不扩授权） */
    private static final Set<String> UNPRIVILEGED = Set.of("MAINT_MGR");

    private MigrationIssueService svc;
    private AdminMigrationIssueController controller;

    @BeforeEach
    void setUp() {
        svc = mock(MigrationIssueService.class);
        controller = new AdminMigrationIssueController(svc);
    }

    /** 请求桩：ATTR_ROLES=规范码集，ATTR_EMP_ID=操作者员工 id */
    private MockHttpServletRequest req(String method, String path, Set<String> roles, String empId) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setAttribute(AuthInterceptor.ATTR_ROLES, roles);
        if (empId != null) {
            r.setAttribute(AuthInterceptor.ATTR_EMP_ID, empId);
        }
        return r;
    }

    /** 无 audit:view → 403，且门控先于任何 service 调用（零副作用） */
    @Test
    void listRequiresAuditViewDeny403() {
        MockHttpServletRequest r = req("GET", "/admin/migration-issues", UNPRIVILEGED, "emp_x");

        BizException ex = assertThrows(BizException.class,
                () -> controller.list(null, null, 1, 20, r));
        assertEquals(403, ex.getCode());
        verifyNoInteractions(svc);
    }

    /** 无 config:manage → 403，且门控先于任何 service 调用（零副作用） */
    @Test
    void resolveRequiresConfigManageDeny403() {
        MockHttpServletRequest r = req("POST", "/admin/migration-issues/mi1/resolve", UNPRIVILEGED, "emp_x");

        BizException ex = assertThrows(BizException.class,
                () -> controller.resolve("mi1", new LinkedHashMap<>(), r));
        assertEquals(403, ex.getCode());
        verifyNoInteractions(svc);
    }

    /** body 各字段 + actor 逐字透传；decision 缺省传 null（非 "null" 字符串） */
    @Test
    void resolvePassesBodyAndActorToService() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", "BACKFILLED");
        body.put("backfillUnitId", "uu1");
        body.put("note", "已核对");
        // decision 缺省 → controller 必须传 null
        when(svc.resolve(eq("mi1"), eq("BACKFILLED"), eq("uu1"), isNull(), eq("已核对"), eq("emp1")))
                .thenReturn(Map.of("ok", true));
        MockHttpServletRequest r = req("POST", "/admin/migration-issues/mi1/resolve", PRIVILEGED, "emp1");

        assertEquals(Map.of("ok", true), controller.resolve("mi1", body, r).getData());

        verify(svc).resolve(eq("mi1"), eq("BACKFILLED"), eq("uu1"), isNull(), eq("已核对"), eq("emp1"));
    }

    /** service.list 原样包装进 ApiResponse.ok；query 参数 type 映射到 service 的 issueType */
    @Test
    void listReturnsServicePage() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("list", List.of(Map.of("id", "mi1")));
        page.put("total", 1);
        page.put("pending", 3);
        when(svc.list("UNMATCHED", "PENDING", 2, 5)).thenReturn(page);
        MockHttpServletRequest r = req("GET", "/admin/migration-issues", PRIVILEGED, "emp1");

        assertEquals(page, controller.list("UNMATCHED", "PENDING", 2, 5, r).getData());

        verify(svc).list("UNMATCHED", "PENDING", 2, 5);
    }
}
