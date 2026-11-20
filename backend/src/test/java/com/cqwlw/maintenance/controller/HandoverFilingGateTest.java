package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ExternalFilingOrder;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.service.ExternalFilingService;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
        for (String role : new String[]{"MAINT_TECH", "MAINT_SUP", "MAINT_MGR", "MAINT_QUALITY", "MAINT_BIZ",
                "INST_CLERK", "INST_MGR", "INSTALLER", "PLATFORM_ADMIN"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> c.init(Map.of("projectName", "演示项目"), req(role)), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(svc, never()).init(any());
        when(svc.init(any())).thenReturn(new HandoverOrder());
        assertNotNull(c.init(Map.of("projectName", "演示项目"), req("INST_PM")));
        verify(svc, times(1)).init(any());
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
}
