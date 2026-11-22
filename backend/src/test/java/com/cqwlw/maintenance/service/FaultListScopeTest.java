package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultListScopeTest {

    private DirectoryService svc;
    private FaultMapper faults;
    private ElevatorMapper elevators;
    private EmployeeMapper employees;
    private EmployeeScopeService scope;
    private WorkOrderService workOrderService;

    @BeforeEach
    void setUp() {
        faults = mock(FaultMapper.class);
        workOrderService = mock(WorkOrderService.class);
        elevators = mock(ElevatorMapper.class);
        employees = mock(EmployeeMapper.class);
        scope = mock(EmployeeScopeService.class);
        svc = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                elevators, mock(ChecklistService.class), workOrderService,
                scope, employees, mock(UseUnitMapper.class),
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                mock(TenantSnapshotService.class));
        when(employees.selectList(any())).thenReturn(List.of());
        Elevator own = new Elevator();
        own.elevatorCode = "EM-1";
        own.regionId = "r1";
        Elevator other = new Elevator();
        other.elevatorCode = "EM-2";
        other.regionId = "r2";
        when(elevators.selectList(any())).thenReturn(List.of(own, other));
        when(elevators.selectOne(any())).thenReturn(own, other);
        Employee me = new Employee();
        me.id = "e1";
        me.role = "MAINT_TECH";
        me.regionId = "r1";
        when(scope.require("e1")).thenReturn(me);
        when(scope.visibleCreatorIds(me)).thenReturn(Set.of("e1"));
        // 管理角色：null = 全可见
        Employee admin = new Employee();
        admin.id = "boss";
        admin.role = "ADMIN";
        when(scope.require("boss")).thenReturn(admin);
        when(scope.visibleCreatorIds(admin)).thenReturn(null);

        Fault cross = fault("f_cross", "unit:uu_2", "OPEN", null);
        cross.elevatorCode = "EM-2";
        when(faults.selectById("f_cross")).thenReturn(cross);
        when(faults.selectList(any())).thenReturn(List.of(
                fault("f_mine", "e1", "CLOSED", "e1"),
                fault("f_pool", "unit:uu_1", "OPEN", null),      // 客户报修·抢单池
                fault("f_myjob", "unit:uu_1", "ASSIGNED", "e1"), // 客户报修·已指派给我
                fault("f_other", "unit:uu_1", "ASSIGNED", "e2"), // 他人已接单
                fault("f_done", "e2", "CLOSED", "e2"),         // 他人闭环单
                cross));                                           // 跨区域 OPEN，不得进入本区域池
        when(workOrderService.paginate(any(), any())).thenAnswer((inv) -> {
            List<Map<String, Object>> views = inv.getArgument(0);
            return Map.of("list", views, "total", views.size());
        });
        when(faults.selectPage(any(), any())).thenReturn(pageOf(List.of(
                fault("f_mine", "e1", "CLOSED", "e1"),
                fault("f_pool", "unit:uu_1", "OPEN", null),
                fault("f_myjob", "unit:uu_1", "ASSIGNED", "e1"),
                fault("f_other", "unit:uu_1", "ASSIGNED", "e2"),
                fault("f_done", "e2", "CLOSED", "e2"), cross), 6));
    }

    @Test
    void restrictedWorkerSeesPoolAndOwnAssigned() {
        when(faults.selectPage(any(), any())).thenReturn(pageOf(List.of(
                fault("f_mine", "e1", "CLOSED", "e1"),
                fault("f_pool", "unit:uu_1", "OPEN", null),
                fault("f_myjob", "unit:uu_1", "ASSIGNED", "e1")), 3));
        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "", "trapped", ""), "e1");
        assertEquals(3, res.get("total")); // f_mine + f_pool + f_myjob
    }

    @Test
    void privilegedSeesAll() {
        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "", "trapped", ""), "boss");
        assertEquals(6, res.get("total"));
    }

    @Test
    void crossRegionOpenFaultIsInvisibleInListAndDetail() {
        when(faults.selectPage(any(), any())).thenReturn(pageOf(List.of(
                fault("f_pool", "unit:uu_1", "OPEN", null)), 1));
        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "OPEN", "trapped", ""), "e1");
        assertEquals(1, res.get("total"));
        Elevator crossElevator = new Elevator();
        crossElevator.elevatorCode = "EM-2";
        crossElevator.regionId = "r2";
        when(elevators.selectOne(any())).thenReturn(crossElevator);
        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> svc.getFault("f_cross", "e1"));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void supervisorKeepsVisibilityAfterClientFaultAssignedToTeam() {
        Employee sup = new Employee();
        sup.id = "sup";
        sup.role = "MAINT_SUP";
        sup.regionId = "r1";
        when(scope.require("sup")).thenReturn(sup);
        when(scope.visibleCreatorIds(sup)).thenReturn(Set.of("e1", "e2"));
        when(faults.selectPage(any(), any())).thenReturn(pageOf(List.of(
                fault("f_myjob", "unit:uu_1", "ASSIGNED", "e1"),
                fault("f_other", "unit:uu_1", "ASSIGNED", "e2")), 2));

        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "ASSIGNED", "trapped", ""), "sup");

        assertEquals(2, res.get("total"));
    }

    @Test
    void crossGroupSupervisorCannotCancelAssignedFault() {
        Employee sup = new Employee();
        sup.id = "sup";
        sup.role = "MAINT_SUP";
        sup.regionId = "r1";
        when(scope.require("sup")).thenReturn(sup);
        when(scope.visibleCreatorIds(sup)).thenReturn(Set.of("e1"));
        Fault assigned = fault("f_other", "unit:uu_1", "ASSIGNED", "e2");
        when(faults.selectById("f_other")).thenReturn(assigned);

        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> svc.cancelFault("f_other", Set.of("MAINT_SUP"), "跨组取消", "sup", null));

        assertEquals(1403, ex.getCode());
    }

    private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<Fault> pageOf(List<Fault> rows, int total) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Fault> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 50);
        page.setRecords(rows);
        page.setTotal(total);
        return page;
    }

    private static Fault fault(String id, String createdBy, String status, String dispatcher) {
        Fault f = new Fault();
        f.id = id;
        f.createdBy = createdBy;
        f.status = status;
        f.elevatorCode = "EM-1";
        f.dispatchWorkerId = dispatcher;
        return f;
    }
}
