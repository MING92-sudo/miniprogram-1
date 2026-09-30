package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派单验收口径（AGENTS §6 / scripts/verify-dispatch.js）：
 * 同项目 6 台同日到期 → 当日一次性全部 09:00 派单；保养类型按时间自动升级。
 */
class DispatchServiceTest {

    private ElevatorMapper elevatorMapper;
    private WorkOrderMapper orderMapper;
    private MessageMapper messageMapper;
    private DispatchService service;

    @BeforeEach
    void setUp() throws Exception {
        elevatorMapper = mock(ElevatorMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        messageMapper = mock(MessageMapper.class);
        ChecklistService checklistService = new ChecklistService();
        checklistService.load();
        service = new DispatchService(elevatorMapper, orderMapper, messageMapper, checklistService);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectList(any())).thenReturn(List.of());
    }

    private Elevator dueElevator(String id) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = "EM-" + id;
        el.elevatorName = "电梯" + id;
        el.category = "曳引驱动电梯";
        el.workTypeCode = "HM";
        el.intervalDays = 15;
        el.workerName = "张伟";
        el.workerPlatformId = "990001";
        // 上次维保 16 天前：半月周期已到期（到期前一天即派单）
        el.lastMaintenanceAt = TimeUtil.now().minusDays(16);
        return el;
    }

    @Test
    void sixElevatorsDueSameDayAllDispatchedAtNine() {
        List<Elevator> elevators = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            elevators.add(dueElevator("el_" + i));
        }
        when(elevatorMapper.selectList(any())).thenReturn(elevators);

        List<WorkOrder> created = service.ensureDueOrders();

        assertEquals(6, created.size());
        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper, times(6)).insert(captor.capture());
        String today = TimeUtil.date(TimeUtil.now());
        for (WorkOrder o : captor.getAllValues()) {
            assertEquals("PENDING", o.status);
            assertEquals(today + " 09:00:00", TimeUtil.format(o.planTime));
            assertEquals("半月维保", o.workType);
            assertEquals(Boolean.TRUE, o.autoDispatched);
        }
        verify(messageMapper, times(6)).insert(any(Message.class));
    }

    @Test
    void typeUpgradesToYearlyWhenDue365Days() {
        Elevator el = dueElevator("el_y");
        el.lastMaintenanceAt = TimeUtil.now().minusDays(366);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        assertEquals("OY", created.get(0).workTypeCode);
        assertEquals("年度维保", created.get(0).workType);
    }

    @Test
    void activeOrderSkipsDispatch() {
        Elevator el = dueElevator("el_a");
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.ensureDueOrders().size());
    }

    @Test
    void notYetDueSkipsDispatch() {
        Elevator el = dueElevator("el_n");
        el.lastMaintenanceAt = TimeUtil.now().minusDays(5); // 15 天周期未到"到期前一天"
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));

        assertEquals(0, service.ensureDueOrders().size());
    }

    @Test
    void dueDayInFuturePlansOnDueDayAtNine() {
        Elevator el = dueElevator("el_f");
        // 上次 14 天前 → 到期日 = 明天（恰好进入"到期前一天"窗口），计划时间应为到期日 09:00
        el.lastMaintenanceAt = TimeUtil.now().minusDays(14);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        String planDay = TimeUtil.date(created.get(0).planTime);
        assertEquals("09:00:00", TimeUtil.format(created.get(0).planTime).substring(11));
        assertEquals(true, planDay.compareTo(TimeUtil.date(TimeUtil.now())) >= 0);
    }
}
