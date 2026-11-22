package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
