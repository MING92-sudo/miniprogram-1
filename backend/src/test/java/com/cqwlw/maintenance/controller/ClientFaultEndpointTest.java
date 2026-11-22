package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.service.UnitPortalService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientFaultEndpointTest {

    @Test
    void clientFaultEndpointsUseOnlySessionUnitAnchor() {
        UnitPortalService portal = mock(UnitPortalService.class);
        UnitPortalController controller = new UnitPortalController(portal, mock(SysUserRoleMapper.class));
        com.cqwlw.maintenance.service.IdempotencyService idempotency = mock(com.cqwlw.maintenance.service.IdempotencyService.class);
        com.cqwlw.maintenance.service.IdempotencyService.Guard guard = mock(com.cqwlw.maintenance.service.IdempotencyService.Guard.class);
        when(idempotency.begin(any(), any())).thenReturn(guard);
        when(guard.replayed()).thenReturn(false);
        controller.setIdempotencyService(idempotency);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_UNIT_ID, "u1");
        request.setAttribute(AuthInterceptor.ATTR_ROLES, Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"));
        when(portal.faultDetail("u1", "f1")).thenReturn(Map.of("id", "f1"));
        when(portal.confirmFault("f1", Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "u1", true, ""))
                .thenReturn(Map.of("status", "CLOSED"));
        when(portal.cancelFault("f1", Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "u1", "误报"))
                .thenReturn(Map.of("status", "CANCELLED"));

        assertEquals("f1", controller.faultDetail("f1", request).getData().get("id"));
        assertEquals("CLOSED", controller.confirmFault("f1", Map.of("pass", true), request, "idem-1").getData().get("status"));
        assertEquals("CANCELLED", controller.cancelFault("f1", Map.of("reason", "误报"), request, "idem-2").getData().get("status"));

        verify(portal).faultDetail("u1", "f1");
        verify(portal).confirmFault("f1", Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "u1", true, "");
        verify(portal).cancelFault("f1", Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "u1", "误报");
    }
}
