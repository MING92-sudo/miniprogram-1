package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.HandoverChecklistTemplate;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 移交单状态机：3 必传资料卡点、安全装置/制动器不合格强制整改、复验通过回待确认。 */
class HandoverServiceTest {

    private static HandoverService service(HandoverOrderMapper orderMapper,
                                           HandoverCheckMapper checkMapper,
                                           HandoverCheckItemMapper checkItemMapper,
                                           HandoverRectificationMapper rectificationMapper,
                                           HandoverSignMapper signMapper) {
        return new HandoverService(orderMapper, checkMapper, checkItemMapper, rectificationMapper, signMapper,
                mock(ElevatorMapper.class), mock(EmployeeMapper.class), mock(WorkOrderMapper.class),
                mock(DispatchService.class), mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));
    }

    private static Map<String, Object> draft(String projectName) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("projectName", projectName);
        body.put("useUnitId", "uu_1");
        body.put("factoryNumber", "SGL-2026-001");
        body.put("insideNumber", "KT-01");
        body.put("supervisionReportFileId", "f1");
        body.put("maintenanceManualFileId", "f2");
        body.put("qualityCertificateFileId", "f3");
        return body;
    }

    @Test
    void initCarriesC12HeaderFields() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));
        Map<String, Object> body = draft("渝北区某项目");
        body.put("installCompleteDate", "2026-11-01");
        body.put("installUnitName", "某安装公司");
        body.put("specialType", "消防员电梯");
        HandoverOrder o = svc.init(body);
        assertEquals("2026-11-01", o.installCompleteDate);
        assertEquals("某安装公司", o.installUnitName);
        assertEquals("消防员电梯", o.specialType);
    }

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

        HandoverOrder created = new HandoverOrder();
        created.id = "ho_legacy";
        created.status = "DOC_SUBMITTING";
        when(orderMapper.selectById(created.id)).thenReturn(created);

        BizException e = assertThrows(BizException.class, () -> svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2")));
        assertEquals(422, e.getCode(), "缺整机质量证明应拦截");
        assertEquals("DOC_SUBMITTING", created.status, "资料不全不得进入查验中");
    }

    @Test
    void confirmRequiresCompleteChecklistAndApprovedSigns() {
        assertConfirmRejected(confirmFixture(false, List.of()));
        assertConfirmRejected(confirmFixture(true, List.of()));
        List<HandoverSign> rejected = new ArrayList<>(approvedSigns());
        rejected.add(sign("SITE_INSPECT", "REJECT"));
        assertConfirmRejected(confirmFixture(true, rejected));

        ConfirmFixture valid = confirmFixture(true, approvedSigns());
        HandoverOrder archived = valid.service.confirm(valid.order.id, Map.of(), "emp_mgr");
        assertEquals("ARCHIVED", archived.status);
        assertEquals("emp_mgr", archived.confirmedById);
        verify(valid.elevatorMapper).insert(any(com.cqwlw.maintenance.entity.Elevator.class));
    }

    private static void assertConfirmRejected(ConfirmFixture fixture) {
        BizException error = assertThrows(BizException.class,
                () -> fixture.service.confirm(fixture.order.id, Map.of(), "emp_mgr"));
        assertEquals(422, error.getCode());
        assertEquals("PENDING_CONFIRM", fixture.order.status);
        assertNull(fixture.order.confirmedById);
        verify(fixture.orderMapper, never()).updateById(any(HandoverOrder.class));
        verify(fixture.elevatorMapper, never()).insert(any(com.cqwlw.maintenance.entity.Elevator.class));
        verify(fixture.workOrderMapper, never()).insert(any(WorkOrder.class));
    }

    private static ConfirmFixture confirmFixture(boolean completeChecklist, List<HandoverSign> signs) {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverCheckMapper checkMapper = mock(HandoverCheckMapper.class);
        HandoverCheckItemMapper itemMapper = mock(HandoverCheckItemMapper.class);
        HandoverRectificationMapper rectMapper = mock(HandoverRectificationMapper.class);
        HandoverSignMapper signMapper = mock(HandoverSignMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        HandoverOrder order = new HandoverOrder();
        order.id = "ho_confirm";
        order.status = "PENDING_CONFIRM";
        order.projectName = "渝北区某项目";
        order.elevatorCode = "130421";
        order.regCode = "REG-2026-001";
        order.deviceCode = "DEV-2026-001";
        order.insideNumber = "KT-01";
        order.factoryNumber = "SGL-2026-001";
        when(orderMapper.selectById(order.id)).thenReturn(order);
        Employee manager = new Employee();
        manager.id = "emp_mgr";
        manager.name = "王经理";
        when(employeeMapper.selectById(manager.id)).thenReturn(manager);
        if (completeChecklist) {
            HandoverCheck check = new HandoverCheck();
            check.id = "hc_complete";
            check.handoverId = order.id;
            check.snapshotJson = "{\"conclusion\":\"QUALIFIED\"}";
            when(checkMapper.selectList(any())).thenReturn(List.of(check));
            List<HandoverCheckItem> items = new ArrayList<>();
            for (HandoverChecklistTemplate.Item template : HandoverChecklistTemplate.items()) {
                HandoverCheckItem item = new HandoverCheckItem();
                item.id = "hi_" + template.no();
                item.checkId = check.id;
                item.itemNo = template.no();
                item.itemGroup = template.group();
                item.result = "PASS";
                items.add(item);
            }
            when(itemMapper.selectList(any())).thenReturn(items);
        }
        when(signMapper.selectList(any())).thenReturn(signs);
        HandoverService service = new HandoverService(orderMapper, checkMapper, itemMapper, rectMapper, signMapper,
                elevatorMapper, employeeMapper, workOrderMapper, mock(DispatchService.class),
                mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));
        return new ConfirmFixture(service, order, orderMapper, elevatorMapper, workOrderMapper);
    }

    private static List<HandoverSign> approvedSigns() {
        return List.of(sign("INSTALLER_HANDOVER", "APPROVE"), sign("MAINTENANCE_ASSIGN", "APPROVE"),
                sign("SITE_INSPECT", "APPROVE"), sign("MAINTENANCE_CONFIRM", "APPROVE"));
    }

    private static HandoverSign sign(String node, String action) {
        HandoverSign sign = new HandoverSign();
        sign.nodeType = node;
        sign.action = action;
        return sign;
    }

    private record ConfirmFixture(HandoverService service, HandoverOrder order, HandoverOrderMapper orderMapper,
                                  ElevatorMapper elevatorMapper, WorkOrderMapper workOrderMapper) {
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
                mock(WorkOrderMapper.class), mock(DispatchService.class), mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));

        HandoverOrder created = new HandoverOrder();
        created.id = "ho_recheck";
        created.status = "RECTIFYING";
        when(orderMapper.selectById(created.id)).thenReturn(created);

        svc.submitRectify(created.id, Map.of("rectifications", List.of(Map.of(
                "itemType", "SAFETY_DEVICE",
                "beforePhotoFileId", "b1",
                "afterPhotoFileId", "a1"))));
        svc.recheckRectify(created.id, Map.of(
                "recheckerId", "r1", "recheckerName", "赵六", "result", "PASS"));

        assertEquals("PENDING_CONFIRM", created.status, "复验通过应回待确认");
    }

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
                mock(com.cqwlw.maintenance.service.DispatchService.class),
                mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));

        Map<String, Object> out = svc.detail("ho_1");
        assertEquals("ho_1", out.get("id"));
        java.util.List<?> checks = (java.util.List<?>) out.get("checks");
        assertEquals(1, checks.size());
        assertEquals(1, ((Map<?, ?>) checks.get(0)).get("items") == null ? 0 : 1);
        assertEquals(0, ((java.util.List<?>) out.get("rectifications")).size());
    }
}
