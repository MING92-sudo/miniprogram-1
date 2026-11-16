package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 终审 I1：客户电梯档案**真实谓词**测试（ElevatorService/EmployeeScopeService 不 mock）。
 * 契约（docs/04 A.12.1①）：客户令牌 `GET /elevators*` 含 profile，仅本单位谓词，跨单位 1403。
 * 旧缺陷：客户员工 platformId 为空 → visibleWorkerPlatformIds 恒 Set.of()（非 null）→
 * 「仅本人负责电梯」谓词对任何已派梯恒 1403，与本分支明文契约矛盾（列表可看、档案 403）。
 */
class ElevatorClientProfileTest {

    private final ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final ElevatorService elevatorService = new ElevatorService(
            elevatorMapper, mock(UseUnitMapper.class), new EmployeeScopeService(employeeMapper));

    private ElevatorController controller() {
        return new ElevatorController(elevatorService, mock(DispatchService.class), userRoleMapper);
    }

    private HttpServletRequest request(Set<String> roles, String empId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(roles);
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn(empId);
        return req;
    }

    private Elevator elevator(String id, String useUnitId, String workerPlatformId) {
        Elevator el = new Elevator();
        el.id = id;
        el.useUnitId = useUnitId;
        el.elevatorName = "验证梯 " + id;
        el.elevatorCode = "CQ-" + id;
        el.workerPlatformId = workerPlatformId;
        return el;
    }

    private void anchor(String unitId) {
        SysUserRole row = new SysUserRole();
        row.orgId = unitId;
        when(userRoleMapper.selectOne(any())).thenReturn(row);
    }

    private Employee employee(String id, String role, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.role = role;
        e.platformId = platformId;
        return e;
    }

    /** 客户（无 platformId、电梯已派给维保班组）看本单位电梯档案 → 200，不得走 worker-scope 谓词恒 1403 */
    @Test
    void clientSameUnitProfileAllowedWithRealPredicate() {
        anchor("uu_1");
        when(elevatorMapper.selectById("el_1")).thenReturn(elevator("el_1", "uu_1", "pid_zhang"));
        when(employeeMapper.selectById("e1")).thenReturn(employee("e1", "CLIENT_ADMIN", null));

        Map<String, Object> data = controller()
                .profile("el_1", request(Set.of("CLIENT_ADMIN"), "e1")).getData();
        assertEquals("el_1", data.get("elevatorId"));
    }

    /** 客户跨单位档案 → 1403（本单位谓词，真实 ElevatorService 校验） */
    @Test
    void clientCrossUnitProfileDenied() {
        anchor("uu_1");
        when(elevatorMapper.selectById("el_9")).thenReturn(elevator("el_9", "uu_2", "pid_zhang"));

        BizException ex = assertThrows(BizException.class,
                () -> controller().profile("el_9", request(Set.of("CLIENT_ADMIN"), "e1")));
        assertEquals(1403, ex.getCode());
    }

    /** 客户无锚点（org_id 缺失）→ 1403 fail-closed，不回退全量 */
    @Test
    void clientWithoutAnchorFailClosed() {
        when(userRoleMapper.selectOne(any())).thenReturn(null);
        when(elevatorMapper.selectById("el_1")).thenReturn(elevator("el_1", "uu_1", "pid_zhang"));

        BizException ex = assertThrows(BizException.class,
                () -> controller().profile("el_1", request(Set.of("CLIENT_SIGNER"), "e1")));
        assertEquals(1403, ex.getCode());
    }

    /** 内部员工 profile 行为逐条不变：主维保 200，无关电梯 1403（V7 严格谓词保留） */
    @Test
    void internalEmployeeWorkerPredicateUnchanged() {
        when(employeeMapper.selectById("e2")).thenReturn(employee("e2", "WORKER", "pid_zhang"));
        when(elevatorMapper.selectById("el_1")).thenReturn(elevator("el_1", "uu_1", "pid_zhang"));
        assertEquals("el_1", controller()
                .profile("el_1", request(Set.of("MAINT_TECH"), "e2")).getData().get("elevatorId"));

        when(elevatorMapper.selectById("el_2")).thenReturn(elevator("el_2", "uu_1", "pid_other"));
        BizException ex = assertThrows(BizException.class,
                () -> controller().profile("el_2", request(Set.of("MAINT_TECH"), "e2")));
        assertEquals(1403, ex.getCode());
    }
}
