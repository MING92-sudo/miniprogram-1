package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.service.UnitPortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无 CLIENT 角色/无锚点 fail-closed 403，不得静默放行。 */
class UnitPortalUnifiedTokenTest {

    private final UnitPortalService portalService = mock(UnitPortalService.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);

    private UnitPortalController controller() {
        return new UnitPortalController(portalService, userRoleMapper);
    }

    private HttpServletRequest employeeRequest(Set<String> roles) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(roles);
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn("e1");
        return req;
    }

    private void anchor(String unitId) {
        SysUserRole row = new SysUserRole();
        row.orgId = unitId;
        when(userRoleMapper.selectOne(any())).thenReturn(row);
    }

    @Test
    void employeeClientTokenResolvesUnitAnchor() {
        anchor("uu_1");
        when(portalService.elevators("uu_1")).thenReturn(List.of(Map.of("id", "el_1")));
        assertEquals(List.of(Map.of("id", "el_1")),
                controller().elevators(employeeRequest(Set.of("CLIENT_ADMIN"))).getData());
        verify(portalService).elevators("uu_1");
    }

    @Test
    void employeePendingSignsAndFaultsUseAnchor() {
        anchor("uu_1");
        controller().pendingSigns(employeeRequest(Set.of("CLIENT_SIGNER")));
        controller().faults(employeeRequest(Set.of("CLIENT_SIGNER")));
        controller().records(employeeRequest(Set.of("CLIENT_SIGNER")));
        verify(portalService).pendingSigns("uu_1");
        verify(portalService).faults("uu_1");
        verify(portalService).records("uu_1");
    }

    @Test
    void employeeReportFaultUsesAnchor() {
        anchor("uu_1");
        Map<String, Object> body = Map.of("elevatorCode", "EM-1");
        controller().reportFault(body, employeeRequest(Set.of("CLIENT_ADMIN")));
        verify(portalService).reportFault("uu_1", body);
    }

    @Test
    void employeeWithoutClientAnchorDenied() {
        when(userRoleMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> controller().elevators(employeeRequest(Set.of("MAINT_TECH"))));
        assertEquals(403, ex.getCode());
        verify(portalService, never()).elevators(anyString());
    }

    @Test
    void legacyPortalTokenStillWorks() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_UNIT_ID)).thenReturn("uu_9");
        controller().elevators(req);
        verify(portalService).elevators("uu_9");
    }
}