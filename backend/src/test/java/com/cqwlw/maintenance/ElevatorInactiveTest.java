package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 电梯停用（status=INACTIVE）后的行为（docs/09 §6.6 / V8 迁移注释：停用后不派单、小程序不可见、二维码失效）：
 * 原实现只在批量绑定处校验，自动派单与电梯列表都没过滤，停用电梯仍会生成工单并触发 2.6 上报。
 */
class ElevatorInactiveTest {

    private static Elevator elevator(String id, String status) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = "CQ-" + id;
        el.elevatorName = "验证梯 " + id;
        el.category = "曳引驱动乘客电梯";
        el.workTypeCode = "HM";
        el.intervalDays = 15;
        el.status = status;
        el.workerName = "张伟";
        el.workerPlatformId = "pid_zhang";
        el.lastMaintenanceAt = TimeUtil.now().minusDays(20); // 已到期：未停用时必定派单
        return el;
    }

    @Test
    void scheduledDispatchSkipsInactiveElevator() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper orderMapper = mock(WorkOrderMapper.class);
        DispatchService dispatch = new DispatchService(elevatorMapper, orderMapper,
                mock(MessageMapper.class), mock(ChecklistService.class), new OrderNoIssuer(orderMapper));
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_off", "INACTIVE")));

        assertTrue(dispatch.ensureDueOrders().isEmpty(), "停用电梯不得自动派单");
        verify(orderMapper, never()).insert(any(com.cqwlw.maintenance.entity.WorkOrder.class));
    }

    @Test
    void scheduledDispatchStillCreatesOrderForActiveElevator() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper orderMapper = mock(WorkOrderMapper.class);
        ChecklistService checklistService = mock(ChecklistService.class);
        when(checklistService.label(any())).thenReturn("半月维保");
        DispatchService dispatch = new DispatchService(elevatorMapper, orderMapper,
                mock(MessageMapper.class), checklistService, new OrderNoIssuer(orderMapper));
        Elevator active = elevator("el_on", null);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(active));
        when(elevatorMapper.selectOne(any())).thenReturn(active);

        assertEquals(1, dispatch.ensureDueOrders().size(), "在保电梯的到期派单不得被停用过滤误伤");
    }

    @Test
    void manualDispatchRejectsInactiveElevator() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        DispatchService dispatch = new DispatchService(elevatorMapper, mock(WorkOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class),
                new OrderNoIssuer(mock(WorkOrderMapper.class)));
        when(elevatorMapper.selectById("el_off")).thenReturn(elevator("el_off", "INACTIVE"));

        BizException e = assertThrows(BizException.class, () -> dispatch.dispatchNow("el_off"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("停用"));
    }

    @Test
    void miniProgramElevatorListHidesInactive() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        EmployeeScopeService scopeService = mock(EmployeeScopeService.class);
        ElevatorService service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService);
        when(elevatorMapper.selectList(any()))
                .thenReturn(List.of(elevator("el_on", null), elevator("el_off", "INACTIVE")));
        // 管理角色不限班组（ids=null），隔离班组权限以单独验证「停用电梯隐藏」
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(null);

        List<Map<String, Object>> list = service.listView("emp_1");
        assertEquals(1, list.size(), "小程序电梯列表不得出现停用电梯");
        assertEquals("el_on", list.get(0).get("id"));
    }

    @Test
    void miniProgramElevatorListScopedToOwnWorker() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        EmployeeScopeService scopeService = mock(EmployeeScopeService.class);
        ElevatorService service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService);

        // 本人作为主维保 / 配合人员 / 无关电梯三种情形（docs/01 §3.4.1「该人员负责的电梯」口径）
        Elevator asWorker = elevator("el_worker", null);   // workerPlatformId = pid_zhang
        Elevator asAssistant = elevator("el_assist", null);
        asAssistant.workerPlatformId = "pid_other";
        asAssistant.assistantPlatformId = "pid_zhang";
        Elevator other = elevator("el_other", null);
        other.workerPlatformId = "pid_other";
        other.assistantPlatformId = null;

        when(elevatorMapper.selectList(any())).thenReturn(List.of(asWorker, asAssistant, other));
        when(scopeService.require("emp_1")).thenReturn(new Employee());
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(Set.of("pid_zhang"));

        List<Map<String, Object>> list = service.listView("emp_1");
        assertEquals(2, list.size(), "作业人员仅可见本人作为主维保或配合人员的电梯");
        assertEquals("el_worker", list.get(0).get("id"));
        assertEquals("el_assist", list.get(1).get("id"));
    }

    @Test
    void updateProjectNameSavesForOwnWorker() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        EmployeeScopeService scopeService = mock(EmployeeScopeService.class);
        ElevatorService service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService);

        when(elevatorMapper.selectById("el_mine")).thenReturn(elevator("el_mine", null));
        when(scopeService.require("emp_1")).thenReturn(new Employee());
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(Set.of("pid_zhang"));

        service.updateProjectName("el_mine", "emp_1", " 世纪大厦 ");

        ArgumentCaptor<Elevator> cap = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).updateById(cap.capture());
        assertEquals("世纪大厦", cap.getValue().projectName, "项目名称应去除首尾空白后落库");
        assertEquals("el_mine", cap.getValue().id);
    }

    @Test
    void updateProjectNameRejectsOtherWorkersElevator() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        EmployeeScopeService scopeService = mock(EmployeeScopeService.class);
        ElevatorService service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService);

        Elevator other = elevator("el_other", null);
        other.workerPlatformId = "pid_other";
        other.assistantPlatformId = null;
        when(elevatorMapper.selectById("el_other")).thenReturn(other);
        when(scopeService.require("emp_1")).thenReturn(new Employee());
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(Set.of("pid_zhang"));

        BizException e = assertThrows(BizException.class,
                () -> service.updateProjectName("el_other", "emp_1", "新名称"));
        assertEquals(1403, e.getCode(), "仅可维护本人负责的电梯");
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
    }

    @Test
    void scanInactiveElevatorQrcodeRejected() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        ElevatorService service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), mock(EmployeeScopeService.class));
        Elevator el = elevator("el_off", "INACTIVE");
        when(elevatorMapper.selectOne(any())).thenReturn(el);

        BizException e = assertThrows(BizException.class, () -> service.viewByCode(el.elevatorCode));
        assertEquals(422, e.getCode(), "停用电梯的贴梯二维码应提示失效（docs/09 §6.6）");
    }
}
