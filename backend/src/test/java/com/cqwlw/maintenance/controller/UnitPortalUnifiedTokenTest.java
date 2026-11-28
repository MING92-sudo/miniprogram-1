package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
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
import static org.mockito.ArgumentMatchers.argThat;
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
    void employeeReportFaultFillsReporterFromClientAccount() {
        anchor("uu_1");
        ClientAccountMapper clientAccountMapper = mock(ClientAccountMapper.class);
        ClientAccount account = new ClientAccount();
        account.name = "王芳";
        account.phone = "13800000003";
        when(clientAccountMapper.selectById("e1")).thenReturn(account);
        UnitPortalController controller = new UnitPortalController(portalService, userRoleMapper);
        controller.setClientAccountMapper(clientAccountMapper);

        controller.reportFault(Map.of("elevatorCode", "EM-1", "reporter", "伪造", "reporterPhone", "13900000000"),
                employeeRequest(Set.of("CLIENT_ADMIN")));

        verify(portalService).reportFault(anyString(), argThat(m ->
                "王芳".equals(m.get("reporter")) && "13800000003".equals(m.get("reporterPhone"))));
    }

    @Test
    void employeeReportFaultFallsBackToEmployeeContact() {
        anchor("uu_1");
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        Employee employee = new Employee();
        employee.name = "李强";
        employee.phone = "13800000004";
        when(employeeMapper.selectById("e1")).thenReturn(employee);
        UnitPortalController controller = new UnitPortalController(portalService, userRoleMapper);
        controller.setEmployeeMapper(employeeMapper);

        controller.reportFault(Map.of("elevatorCode", "EM-1"), employeeRequest(Set.of("CLIENT_ADMIN")));

        verify(portalService).reportFault(anyString(), argThat(m ->
                "李强".equals(m.get("reporter")) && "13800000004".equals(m.get("reporterPhone"))));
    }

    @Test
    void legacyPortalReportFaultFallsBackToUnitContact() {
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        UseUnit unit = new UseUnit();
        unit.elevatorAdminister = "王芳";
        unit.elevatorAdministerPhone = "13800000003";
        when(useUnitMapper.selectById("uu_9")).thenReturn(unit);
        UnitPortalController controller = new UnitPortalController(portalService, userRoleMapper);
        controller.setUseUnitMapper(useUnitMapper);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.ATTR_UNIT_ID)).thenReturn("uu_9");

        controller.reportFault(Map.of("elevatorCode", "EM-1"), request);

        verify(portalService).reportFault(anyString(), argThat(m ->
                "王芳".equals(m.get("reporter")) && "13800000003".equals(m.get("reporterPhone"))));
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