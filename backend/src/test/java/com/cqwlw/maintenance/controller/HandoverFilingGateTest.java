package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ExternalFilingOrder;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.service.ExternalFilingService;
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

/**
 * 移交/外接建档端点门禁（2026-11-21 对齐矩阵 §4.1 / M0 §2.2，17 账号矩阵驱动实测发现的缺口）：
 * POST /handovers 需 handover:create（仅 INST_PM）；POST /filings 需 filing:write（仅 PA/MM/MS/MB）；
 * GET /filings(/id) 需 filing:view（Y=PA/MM/MS/MB，C=MQ/IM/IP/IC）。越权一律 403 且 service 零调用。
 */
class HandoverFilingGateTest {

    private static MockHttpServletRequest req(String... roles) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthInterceptor.ATTR_ROLES, Set.of(roles));
        return r;
    }

    @Test
    void handoverInitGatedToInstPm() {
        HandoverService svc = mock(HandoverService.class);
        HandoverController c = new HandoverController(svc);
        // 2026-11-21 统一后平台管理员=唯一全权角色（含 handover:create），负例不再含 PLATFORM_ADMIN
        for (String role : new String[]{"MAINT_TECH", "MAINT_SUP", "MAINT_MGR", "MAINT_QUALITY", "MAINT_BIZ",
                "INST_MGR", "INSTALLER"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> c.init(Map.of("projectName", "演示项目"), req(role)), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(svc, never()).init(any());
        when(svc.init(any())).thenReturn(new HandoverOrder());
        // 2026-11-21 裁决：handover:create 扩 INST_CLERK（资料员可起草）
        assertNotNull(c.init(Map.of("projectName", "演示项目"), req("INST_PM")));
        assertNotNull(c.init(Map.of("projectName", "演示项目"), req("INST_CLERK")));
        verify(svc, times(2)).init(any());
    }

    @Test
    void filingInitGatedToFilingWrite() {
        ExternalFilingService svc = mock(ExternalFilingService.class);
        ExternalFilingController c = new ExternalFilingController(svc);
        for (String role : new String[]{"MAINT_TECH", "INSTALLER", "MAINT_QUALITY",
                "INST_MGR", "INST_PM", "INST_CLERK"}) {
            BizException ex = assertThrows(BizException.class, () -> c.init(Map.of(), req(role)), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(svc, never()).init(any());
        when(svc.init(any())).thenReturn(new ExternalFilingOrder());
        for (String role : new String[]{"MAINT_MGR", "MAINT_SUP", "MAINT_BIZ", "PLATFORM_ADMIN", "SYS_ADMIN"}) {
            assertNotNull(c.init(Map.of(), req(role)), role);
        }
        verify(svc, times(5)).init(any());
    }

    @Test
    void filingReadGatedToFilingView() {
        ExternalFilingService svc = mock(ExternalFilingService.class);
        ExternalFilingController c = new ExternalFilingController(svc);
        for (String role : new String[]{"MAINT_TECH", "INSTALLER"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> c.list(null, null, null, req(role)), role);
            assertEquals(403, ex.getCode(), role);
            ex = assertThrows(BizException.class, () -> c.get("ef_1", req(role)), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(svc, never()).list(any());
        verify(svc, never()).detail(any());
        when(svc.list(any())).thenReturn(Map.of());
        when(svc.detail(any())).thenReturn(Map.of());
        // Y：PA/MM/MS/MB；C：MQ/IM/IP/IC（requireRead 放行 Y∪C）
        for (String role : new String[]{"MAINT_MGR", "MAINT_SUP", "MAINT_BIZ", "PLATFORM_ADMIN", "SYS_ADMIN",
                "MAINT_QUALITY", "INST_MGR", "INST_PM", "INST_CLERK"}) {
            assertNotNull(c.list(null, null, null, req(role)), role);
            assertNotNull(c.get("ef_1", req(role)), role);
        }
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
    void everyLegacyFilingWriteRequiresFilingWrite() {
        ExternalFilingService svc = mock(ExternalFilingService.class);
        ExternalFilingController c = new ExternalFilingController(svc);
        List<Runnable> calls = List.of(
                () -> c.submitDocs("f1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.takeoverCheck("f1", Map.of(), req("CLIENT_ADMIN")),
                () -> c.confirm("f1", Map.of(), req("CLIENT_ADMIN")));
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

        ExternalFilingService filing = mock(ExternalFilingService.class);
        ExternalFilingController filingController = new ExternalFilingController(filing);
        MockHttpServletRequest filingRequest = req("MAINT_MGR");
        filingRequest.setAttribute(AuthInterceptor.ATTR_EMP_ID, "emp_manager");
        when(filing.confirm(any(), any(), any())).thenReturn(new com.cqwlw.maintenance.entity.HandoverConfirmation());
        filingController.confirm("f1", Map.of("confirmedById", "forged"), filingRequest);
        verify(filing).confirm("f1", Map.of("confirmedById", "forged"), "emp_manager");
    }
}
