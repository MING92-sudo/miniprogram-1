package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
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

/** 2026-11-15 裁决：使用单位账号电梯列表/档案收敛本单位，跨单位 1403，无锚点 fail-closed。 */
class ElevatorClientScopeTest {

    private final ElevatorService elevatorService = mock(ElevatorService.class);
    private final DispatchService dispatchService = mock(DispatchService.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);

    private ElevatorController controller() {
        return new ElevatorController(elevatorService, dispatchService, userRoleMapper);
    }

    private HttpServletRequest request(Set<String> roles) {
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
    void clientListUsesUnitPredicate() {
        anchor("uu_1");
        when(elevatorService.listViewForUnit("uu_1")).thenReturn(List.of(Map.of("id", "el_1")));
        controller().list(request(Set.of("CLIENT_ADMIN")));
        verify(elevatorService).listViewForUnit("uu_1");
        verify(elevatorService, never()).listView(anyString());
    }

    @Test
    void clientWithoutAnchorGetsEmptyList() {
        when(userRoleMapper.selectOne(any())).thenReturn(null);
        when(elevatorService.listViewForUnit(null)).thenReturn(List.of());
        assertEquals(List.of(), controller().list(request(Set.of("CLIENT_SIGNER"))).getData());
    }

    @Test
    void clientProfileCrossUnitDenied() {
        anchor("uu_1");
        Elevator el = new Elevator();
        el.id = "el_9";
        el.useUnitId = "uu_2";
        when(elevatorService.get("el_9")).thenReturn(el);
        BizException ex = assertThrows(BizException.class,
                () -> controller().profile("el_9", request(Set.of("CLIENT_ADMIN"))));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void clientProfileSameUnitAllowed() {
        anchor("uu_1");
        Elevator el = new Elevator();
        el.id = "el_1";
        el.useUnitId = "uu_1";
        when(elevatorService.get("el_1")).thenReturn(el);
        when(elevatorService.profile("el_1", "e1")).thenReturn(Map.of("elevatorId", "el_1"));
        assertEquals("el_1", controller().profile("el_1", request(Set.of("CLIENT_ADMIN"))).getData().get("elevatorId"));
    }

    @Test
    void nonClientKeepsWorkerPredicate() {
        controller().list(request(Set.of("MAINT_TECH")));
        verify(elevatorService).listView("e1");
        verify(elevatorService, never()).listViewForUnit(anyString());
    }
}