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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派单验收口径（AGENTS §6 / scripts/verify-dispatch.js）：
 * 同项目 6 台同日到期 → 当日一次性全部 09:00 派单；保养类型按时间自动升级；
 * 创建前必须先对电梯行加锁（并发跑批不得为同一台电梯重复派单）。
 */
class DispatchServiceTest {

    private ElevatorMapper elevatorMapper;
    private WorkOrderMapper orderMapper;
    private MessageMapper messageMapper;
    private DispatchService service;
    private final AtomicInteger lockCursor = new AtomicInteger();

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

    private void givenElevators(List<Elevator> list) {
        when(elevatorMapper.selectList(any())).thenReturn(list);
        lockCursor.set(0);
        when(elevatorMapper.selectOne(any())).thenAnswer(inv -> {
            int i = lockCursor.getAndIncrement();
            return list.isEmpty() ? null : list.get(Math.min(i, list.size() - 1));
        });
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
        List<Elevator> elevators = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            elevators.add(dueElevator("el_" + i));
        }
        givenElevators(elevators);

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
        givenElevators(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        assertEquals("OY", created.get(0).workTypeCode);
        assertEquals("年度维保", created.get(0).workType);
    }

    @Test
    void activeOrderSkipsDispatch() {
        Elevator el = dueElevator("el_a");
        givenElevators(List.of(el));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.ensureDueOrders().size());
    }

    @Test
    void notYetDueSkipsDispatch() {
        Elevator el = dueElevator("el_n");
        el.lastMaintenanceAt = TimeUtil.now().minusDays(5); // 15 天周期未到"到期前一天"
        givenElevators(List.of(el));

        assertEquals(0, service.ensureDueOrders().size());
    }

    @Test
    void locksElevatorRowBeforeCreatingOrder() {
        // 回归：派单必须先对电梯行 SELECT ... FOR UPDATE，否则并发跑批会为同一台电梯
        // 各插一张工单（重复派单 → 同一梯周期产生两条 2.6 上报）
        Elevator el = dueElevator("el_lock");
        givenElevators(List.of(el));

        service.ensureDueOrders();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(elevatorMapper).selectOne(cap.capture());
        assertTrue(String.valueOf(lastSqlOf(cap.getValue())).toUpperCase().contains("FOR UPDATE"),
                "派单前必须以 SELECT ... FOR UPDATE 锁定电梯行");
    }

    /** 只取 .last(...) 片段，避免解析 lambda 触发 MyBatis-Plus 实体缓存（单测未初始化） */
    private static Object lastSqlOf(Object wrapper) {
        for (Class<?> c = wrapper.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("lastSql");
                f.setAccessible(true);
                return f.get(wrapper);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) {
                return "";
            }
        }
        return "";
    }

    @Test
    void dueDayInFuturePlansOnDueDayAtNine() {
        Elevator el = dueElevator("el_f");
        // 上次 14 天前 → 到期日 = 明天（恰好进入"到期前一天"窗口），计划时间应为到期日 09:00
        el.lastMaintenanceAt = TimeUtil.now().minusDays(14);
        givenElevators(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        String planDay = TimeUtil.date(created.get(0).planTime);
        assertEquals("09:00:00", TimeUtil.format(created.get(0).planTime).substring(11));
        assertTrue(planDay.compareTo(TimeUtil.date(TimeUtil.now())) >= 0);
    }
}