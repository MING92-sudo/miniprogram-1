package com.cqwlw.maintenance.service;

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

/**
 * 急修列表可见域（2026-11-21 客户报修→维保接单通道修复）：
 * 受限员工（V7 创建人收敛）仍须可见 ① OPEN 抢单池单（含客户报修 createdBy=unit:*）
 * ② 指派给本人的单；他人已接单/已闭环的他人单不可见。管理角色（ids==null）全可见不变。
 */
class FaultListScopeTest {

    private DirectoryService svc;

    @BeforeEach
    void setUp() {
        FaultMapper faults = mock(FaultMapper.class);
        WorkOrderService workOrderService = mock(WorkOrderService.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        EmployeeMapper employees = mock(EmployeeMapper.class);
        EmployeeScopeService scope = mock(EmployeeScopeService.class);
        svc = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                elevators, mock(ChecklistService.class), workOrderService,
                scope, employees, mock(UseUnitMapper.class),
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                mock(TenantSnapshotService.class));
        when(elevators.selectList(any())).thenReturn(List.of());
        when(employees.selectList(any())).thenReturn(List.of());
        // 受限员工 e1：只见自己创建的单（V7 收敛）
        Employee me = new Employee();
        me.id = "e1";
        me.role = "MAINT_TECH";
        when(scope.require("e1")).thenReturn(me);
        when(scope.visibleCreatorIds(me)).thenReturn(Set.of("e1"));
        // 管理角色：null = 全可见
        Employee admin = new Employee();
        admin.id = "boss";
        admin.role = "ADMIN";
        when(scope.require("boss")).thenReturn(admin);
        when(scope.visibleCreatorIds(admin)).thenReturn(null);

        when(faults.selectList(any())).thenReturn(List.of(
                fault("f_mine", "e1", "CLOSED", "e1"),
                fault("f_pool", "unit:uu_1", "OPEN", null),      // 客户报修·抢单池
                fault("f_myjob", "unit:uu_1", "ASSIGNED", "e1"), // 客户报修·已指派给我
                fault("f_other", "unit:uu_1", "ASSIGNED", "e2"), // 他人已接单
                fault("f_done", "e2", "CLOSED", "e2")));         // 他人闭环单
        when(workOrderService.paginate(any(), any())).thenAnswer((inv) -> {
            List<Map<String, Object>> views = inv.getArgument(0);
            return Map.of("list", views, "total", views.size());
        });
    }

    @Test
    void restrictedWorkerSeesPoolAndOwnAssigned() {
        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "", "trapped", ""), "e1");
        assertEquals(3, res.get("total")); // f_mine + f_pool + f_myjob
    }

    @Test
    void privilegedSeesAll() {
        Map<String, Object> res = svc.listFaults(Map.of("page", "1", "size", "50", "status", "", "trapped", ""), "boss");
        assertEquals(5, res.get("total"));
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
