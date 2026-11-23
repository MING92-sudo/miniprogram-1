package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.service.HandoverChecklistTemplate;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoverGateTest {

    private static MockHttpServletRequest req(String... roles) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthInterceptor.ATTR_ROLES, Set.of(roles));
        return r;
    }

    @Test
    void handoverInitGatedToInstPm() {
        HandoverService svc = mock(HandoverService.class);
        HandoverController c = new HandoverController(svc);
        for (String role : new String[]{"MAINT_TECH", "MAINT_SUP", "MAINT_MGR", "MAINT_QUALITY", "MAINT_BIZ",
                "INST_MGR", "INSTALLER"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> c.init(Map.of("projectName", "演示项目"), req(role)), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(svc, never()).init(any());
        when(svc.init(any())).thenReturn(new HandoverOrder());
        assertNotNull(c.init(Map.of("projectName", "演示项目"), req("INST_PM")));
        assertNotNull(c.init(Map.of("projectName", "演示项目"), req("INST_CLERK")));
        verify(svc, times(2)).init(any());
    }

    @Test
    void everyLegacyHandoverWriteRequiresItsOwnCapability() {
        HandoverService svc = mock(HandoverService.class);
        HandoverController c = new HandoverController(svc);
        List<Runnable> calls = List.of(
                () -> c.submitDocs("h1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.assignReceiver("h1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.submitInspect("h1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.submitRectify("h1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.recheckRectify("h1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.confirm("h1", Map.of(), req("CLIENT_ADMIN")));
        for (Runnable call : calls) {
            BizException ex = assertThrows(BizException.class, call::run);
            assertEquals(403, ex.getCode());
        }
        org.mockito.Mockito.verifyNoInteractions(svc);
    }

    @Test
    void recheckUsesInspectCapabilityForBothEndpoints() {
        HandoverService handover = mock(HandoverService.class);
        HandoverController legacy = new HandoverController(handover);
        when(handover.recheckRectify(any(), any())).thenReturn(new HandoverOrder());
        assertNotNull(legacy.recheckRectify("h1", Map.of(), req("MAINT_TECH")));
        assertNotNull(legacy.recheckRectify("h1", Map.of(), req("INSTALLER")));
        BizException denied = assertThrows(BizException.class,
                () -> legacy.recheckRectify("h1", Map.of(), req("INST_MGR")));
        assertEquals(403, denied.getCode());
        verify(handover, times(2)).recheckRectify(any(), any());

        com.cqwlw.maintenance.service.HandoverChecklistService checklist =
                mock(com.cqwlw.maintenance.service.HandoverChecklistService.class);
        HandoverChecklistController items = new HandoverChecklistController(checklist,
                mock(com.cqwlw.maintenance.service.HandoverPdfService.class),
                mock(com.cqwlw.maintenance.mapper.HandoverRectificationMapper.class));
        when(checklist.recheckItem(any(), any(), any())).thenReturn(new HandoverOrder());
        assertNotNull(items.recheck("h1", "r1", Map.of(), req("MAINT_TECH")));
        assertNotNull(items.recheck("h1", "r1", Map.of(), req("INSTALLER")));
        BizException itemDenied = assertThrows(BizException.class,
                () -> items.recheck("h1", "r1", Map.of(), req("INST_MGR")));
        assertEquals(403, itemDenied.getCode());
        verify(checklist, times(2)).recheckItem(any(), any(), any());
    }

    @Test
    void naApprovalStillUsesReviewCapability() {
        com.cqwlw.maintenance.service.HandoverChecklistService checklist =
                mock(com.cqwlw.maintenance.service.HandoverChecklistService.class);
        HandoverChecklistController items = new HandoverChecklistController(checklist,
                mock(com.cqwlw.maintenance.service.HandoverPdfService.class),
                mock(com.cqwlw.maintenance.mapper.HandoverRectificationMapper.class));
        when(checklist.naApprove(any(), any())).thenReturn(new com.cqwlw.maintenance.entity.HandoverSign());
        assertNotNull(items.naApprove("h1", Map.of(), req("INST_MGR")));
        BizException denied = assertThrows(BizException.class,
                () -> items.naApprove("h1", Map.of(), req("MAINT_TECH")));
        assertEquals(403, denied.getCode());
    }

    @Test
    void legacyInspectCannotBypass74Items() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        HandoverCheckMapper checkMapper = mock(HandoverCheckMapper.class);
        HandoverCheckItemMapper itemMapper = mock(HandoverCheckItemMapper.class);
        HandoverRectificationMapper rectMapper = mock(HandoverRectificationMapper.class);
        HandoverSignMapper signMapper = mock(HandoverSignMapper.class);
        HandoverOrder order = new HandoverOrder();
        order.id = "h1";
        order.status = "INSPECTING";
        when(orderMapper.selectById("h1")).thenReturn(order);
        HandoverCheck check = new HandoverCheck();
        check.id = "hc1";
        check.handoverId = "h1";
        when(checkMapper.selectList(any())).thenReturn(List.of(check));
        when(itemMapper.selectCount(any())).thenReturn(74L);
        List<HandoverCheckItem> items = new ArrayList<>();
        for (HandoverChecklistTemplate.Item template : HandoverChecklistTemplate.items()) {
            HandoverCheckItem item = new HandoverCheckItem();
            item.id = "hi_" + template.no();
            item.checkId = check.id;
            item.itemNo = template.no();
            item.itemGroup = template.group();
            item.important = template.important();
            items.add(item);
        }
        when(itemMapper.selectList(any())).thenReturn(items);
        HandoverService service = new HandoverService(orderMapper, checkMapper, itemMapper, rectMapper, signMapper,
                mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class),
                mock(com.cqwlw.maintenance.mapper.EmployeeMapper.class),
                mock(com.cqwlw.maintenance.mapper.WorkOrderMapper.class),
                mock(com.cqwlw.maintenance.service.DispatchService.class),
                mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));
        HandoverController controller = new HandoverController(service);

        BizException empty = assertThrows(BizException.class,
                () -> controller.submitInspect("h1", Map.of("items", List.of()), req("MAINT_TECH")));
        BizException unknown = assertThrows(BizException.class,
                () -> controller.submitInspect("h1", Map.of("items",
                        List.of(Map.of("itemNo", 1, "result", "UNKNOWN"))), req("MAINT_TECH")));

        assertEquals(422, empty.getCode());
        assertEquals(422, unknown.getCode());
        assertEquals("INSPECTING", order.status);
        verify(orderMapper, never()).updateById(any(HandoverOrder.class));
        verify(checkMapper, never()).insert(any(HandoverCheck.class));
        verify(checkMapper, never()).updateById(any(HandoverCheck.class));
        verify(itemMapper, never()).insert(any(HandoverCheckItem.class));
        verify(itemMapper, never()).updateById(any(HandoverCheckItem.class));
        verify(rectMapper, never()).insert(any(HandoverRectification.class));
    }

    @Test
    void confirmationActorComesFromAuthenticatedRequest() {
        HandoverService handover = mock(HandoverService.class);
        HandoverController handoverController = new HandoverController(handover);
        MockHttpServletRequest handoverRequest = req("MAINT_MGR");
        handoverRequest.setAttribute(AuthInterceptor.ATTR_EMP_ID, "emp_manager");
        when(handover.confirm(any(), any(), any())).thenReturn(new HandoverOrder());
        handoverController.confirm("h1", Map.of("confirmedById", "forged"), handoverRequest);
        verify(handover).confirm("h1", Map.of("confirmedById", "forged"), "emp_manager");
    }
}
