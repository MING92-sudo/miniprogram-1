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
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));

        HandoverOrder created = svc.init(Map.of("projectName", "渝北区某项目"));
        when(orderMapper.selectById(created.id)).thenReturn(created);
        svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2",
                "qualityCertificateFileId", "f3"));

        svc.submitInspect(created.id, Map.of(
                "checkerId", "c1", "checkerName", "王五",
                "items", List.of(Map.of("itemType", "SAFETY_DEVICE", "result", "FAIL"))));

        assertEquals("RECTIFYING", created.status, "安全装置不合格应强制整改");
    }

    /** 整改 → 复验通过 → 回 PENDING_CONFIRM */
    @Test
    void recheckReturnsToPendingConfirm() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverService svc = service(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class));

        HandoverOrder created = svc.init(Map.of("projectName", "渝北区某项目"));
        when(orderMapper.selectById(created.id)).thenReturn(created);
        svc.submitDocs(created.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2",
                "qualityCertificateFileId", "f3"));
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
