package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 移交单状态机（Task 4）：3 必传资料卡点、安全装置/制动器不合格强制整改、复验通过回待确认。
 * 归档副作用（建电梯/首保工单）在 Task 5 archive()，本测试只验证到 confirm 的状态流转。
 */
class HandoverServiceTest {

    private static HandoverService service(HandoverOrderMapper orderMapper,
                                           HandoverCheckMapper checkMapper,
                                           HandoverCheckItemMapper checkItemMapper,
                                           HandoverRectificationMapper rectificationMapper,
                                           HandoverSignMapper signMapper) {
        return new HandoverService(orderMapper, checkMapper, checkItemMapper, rectificationMapper, signMapper,
                mock(ElevatorMapper.class), mock(EmployeeMapper.class), mock(WorkOrderMapper.class),
                mock(DispatchService.class));
    }

    /** C-12：init 接收表单 §3.3 表头三字段（安装竣工日期/安装单位名称/特殊类别） */
    @Test
    void initCarriesC12HeaderFields() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));
        HandoverOrder o = svc.init(Map.of("projectName", "渝北区某项目",
                "installCompleteDate", "2026-11-01", "installUnitName", "某安装公司", "specialType", "消防员电梯"));
        assertEquals("2026-11-01", o.installCompleteDate);
        assertEquals("某安装公司", o.installUnitName);
        assertEquals("消防员电梯", o.specialType);
    }

    /**
     * 2026-11-20 真机反馈③：纯维保部角色列表默认只返回维保验收阶段
     * （INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED），安装内部阶段
     * （INIT/DOC_SUBMITTING/PENDING_INST_REVIEW）不下发；显式 status 查询尊重入参；
     * 安装部/平台/混合角色不过滤。
     */
    @Test
    void listScopesMaintenanceOnlyRolesToAcceptanceStages() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));
        java.util.function.Function<String, HandoverOrder> orderOf = (st) -> {
            HandoverOrder o = new HandoverOrder();
            o.id = "h_" + st;
            o.status = st;
            o.createdAt = java.time.LocalDateTime.of(2026, 11, 20, 10, 0);
            return o;
        };
        List<HandoverOrder> all = List.of(orderOf.apply("INIT"), orderOf.apply("DOC_SUBMITTING"),
                orderOf.apply("PENDING_INST_REVIEW"), orderOf.apply("INSPECTING"),
                orderOf.apply("RECTIFYING"), orderOf.apply("PENDING_CONFIRM"), orderOf.apply("ARCHIVED"),
                orderOf.apply("VOIDED"));
        when(orderMapper.selectList(any())).thenReturn(all);

        // 纯维保部：隐藏安装内部 3 阶段
        Map<String, Object> out = svc.list(Map.of("page", "1", "size", "20"),
                java.util.Set.of("MAINT_TECH"));
        List<?> ids = ((List<?>) out.get("list")).stream()
                .map(o -> ((HandoverOrder) o).id).toList();
        assertEquals(List.of("h_INSPECTING", "h_RECTIFYING", "h_PENDING_CONFIRM", "h_ARCHIVED"), ids);

        // 显式 status=ARCHIVED 尊重入参
        Map<String, Object> archived = svc.list(Map.of("status", "ARCHIVED", "page", "1", "size", "20"),
                java.util.Set.of("MAINT_TECH"));
        assertEquals(1, ((List<?>) archived.get("list")).size());

        // 平台管理不过滤；安装+维保混合角色不过滤
        Map<String, Object> platform = svc.list(Map.of("page", "1", "size", "20"),
                java.util.Set.of("PLATFORM_ADMIN"));
        assertEquals(8, ((List<?>) platform.get("list")).size());
        Map<String, Object> mixed = svc.list(Map.of("page", "1", "size", "20"),
                java.util.Set.of("MAINT_TECH", "INST_PM"));
        assertEquals(8, ((List<?>) mixed.get("list")).size());

        // 旧签名（无角色）不过滤，行为不变
        Map<String, Object> legacy = svc.list(Map.of("page", "1", "size", "20"));
        assertEquals(8, ((List<?>) legacy.get("list")).size());
    }

    /** init 后 submitDocs 缺整机质量证明 → 422 拦截（3 必传资料卡点） */
    @Test
    void submitDocsBlocksOnMissingRequired() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));

        HandoverOrder created = svc.init(Map.of("projectName", " 渝北区某项目 "));
        when(orderMapper.selectById(created.id)).thenReturn(created);

        BizException e = assertThrows(BizException.class, () -> svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2")));
        assertEquals(422, e.getCode(), "缺整机质量证明应拦截");
        assertEquals("DOC_SUBMITTING", created.status, "资料不全不得进入查验中");
    }

    /** 安全装置不合格 → 强制转 RECTIFYING */
    @Test
    void inspectForceRectifyOnSafetyFail() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        com.cqwlw.maintenance.entity.Employee reviewer = new com.cqwlw.maintenance.entity.Employee();
        reviewer.id = "im"; reviewer.name = "安装主管"; reviewer.role = "INST_MGR";
        when(employeeMapper.selectById("im")).thenReturn(reviewer);
        HandoverService svc = new HandoverService(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class), mock(ElevatorMapper.class), employeeMapper,
                mock(WorkOrderMapper.class), mock(DispatchService.class));

        HandoverOrder created = svc.init(Map.of("projectName", "渝北区某项目"));
        when(orderMapper.selectById(created.id)).thenReturn(created);
        svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2",
                "qualityCertificateFileId", "f3"));
        // C-13：资料齐全进入待审核，审核通过后才进入查验中
        svc.review(created.id, Map.of("action", "APPROVE"), "im");

        svc.submitInspect(created.id, Map.of(
                "checkerId", "c1", "checkerName", "王五",
                "items", List.of(Map.of("itemType", "SAFETY_DEVICE", "result", "FAIL"))));

        assertEquals("RECTIFYING", created.status, "安全装置不合格应强制整改");
    }

    /** 整改 → 复验通过 → 回 PENDING_CONFIRM */
    @Test
    void recheckReturnsToPendingConfirm() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        com.cqwlw.maintenance.entity.Employee reviewer = new com.cqwlw.maintenance.entity.Employee();
        reviewer.id = "im"; reviewer.name = "安装主管"; reviewer.role = "INST_MGR";
        when(employeeMapper.selectById("im")).thenReturn(reviewer);
        HandoverService svc = new HandoverService(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class), mock(ElevatorMapper.class), employeeMapper,
                mock(WorkOrderMapper.class), mock(DispatchService.class));

        HandoverOrder created = svc.init(Map.of("projectName", "渝北区某项目"));
        when(orderMapper.selectById(created.id)).thenReturn(created);
        svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2",
                "qualityCertificateFileId", "f3"));
        svc.review(created.id, Map.of("action", "APPROVE"), "im"); // C-13 审核通过
        svc.submitInspect(created.id, Map.of(
                "checkerId", "c1", "checkerName", "王五",
                "items", List.of(Map.of("itemType", "SAFETY_DEVICE", "result", "FAIL"))));
        assertEquals("RECTIFYING", created.status);

        svc.submitRectify(created.id, Map.of("rectifications", List.of(Map.of(
                "itemType", "SAFETY_DEVICE",
                "beforePhotoFileId", "b1",
                "afterPhotoFileId", "a1"))));
        svc.recheckRectify(created.id, Map.of(
                "recheckerId", "r1", "recheckerName", "赵六", "result", "PASS"));

        assertEquals("PENDING_CONFIRM", created.status, "复验通过应回待确认");
    }

    /** 管理端列表（Task 14）：status 过滤 + 发起时间倒序 */
    @Test
    void listFiltersByStatusAndSortsByCreatedAt() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverOrder old = new HandoverOrder();
        old.id = "ho_old";
        old.status = "PENDING_CONFIRM";
        old.createdAt = java.time.LocalDateTime.of(2026, 1, 1, 9, 0);
        HandoverOrder recent = new HandoverOrder();
        recent.id = "ho_new";
        recent.status = "PENDING_CONFIRM";
        recent.createdAt = java.time.LocalDateTime.of(2026, 1, 2, 9, 0);
        HandoverOrder other = new HandoverOrder();
        other.id = "ho_x";
        other.status = "INSPECTING";
        other.createdAt = java.time.LocalDateTime.of(2026, 1, 3, 9, 0);
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of(old, recent, other));
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));

        Map<String, Object> out = svc.list(Map.of("status", "PENDING_CONFIRM"));
        java.util.List<?> rows = (java.util.List<?>) out.get("list");
        assertEquals(2, rows.size());
        assertEquals("ho_new", ((HandoverOrder) rows.get(0)).id, "应按发起时间倒序");
        assertEquals(2, out.get("total"));
    }

    /** 详情带子资源（Task 14）：单据字段仍在顶层，另出 checks/rectifications/signs */
    @Test
    void detailCarriesSubResources() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverCheckMapper checkMapper = mock(HandoverCheckMapper.class);
        HandoverCheckItemMapper itemMapper = mock(HandoverCheckItemMapper.class);
        HandoverRectificationMapper rectMapper = mock(HandoverRectificationMapper.class);
        HandoverSignMapper signMapper = mock(HandoverSignMapper.class);
        HandoverOrder o = new HandoverOrder();
        o.id = "ho_1";
        o.status = "INSPECTING";
        when(orderMapper.selectById("ho_1")).thenReturn(o);
        com.cqwlw.maintenance.entity.HandoverCheck c = new com.cqwlw.maintenance.entity.HandoverCheck();
        c.id = "hc_1";
        c.handoverId = "ho_1";
        when(checkMapper.selectList(any())).thenReturn(java.util.List.of(c));
        com.cqwlw.maintenance.entity.HandoverCheckItem it = new com.cqwlw.maintenance.entity.HandoverCheckItem();
        it.id = "hi_1";
        it.checkId = "hc_1";
        it.itemType = "BRAKE";
        it.result = "FAIL";
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of(it));
        when(rectMapper.selectList(any())).thenReturn(java.util.List.of());
        when(signMapper.selectList(any())).thenReturn(java.util.List.of());

        HandoverService svc = new HandoverService(orderMapper, checkMapper, itemMapper, rectMapper, signMapper,
                mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class),
                mock(com.cqwlw.maintenance.mapper.EmployeeMapper.class),
                mock(com.cqwlw.maintenance.mapper.WorkOrderMapper.class),
                mock(com.cqwlw.maintenance.service.DispatchService.class));

        Map<String, Object> out = svc.detail("ho_1");
        assertEquals("ho_1", out.get("id"));
        java.util.List<?> checks = (java.util.List<?>) out.get("checks");
        assertEquals(1, checks.size());
        assertEquals(1, ((Map<?, ?>) checks.get(0)).get("items") == null ? 0 : 1);
        assertEquals(0, ((java.util.List<?>) out.get("rectifications")).size());
    }
}
