package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.MigrationIssueService;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.service.TenantSnapshotService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private EmployeeMapper employeeMapper;
    private DispatchService service;
    private List<Elevator> rows = List.of();
    private final java.util.concurrent.atomic.AtomicInteger lockCursor =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯 Mockito 单测无 MyBatis 环境，getSqlSegment() 需 TableInfo 缓存，手动初始化一次
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkOrder.class);
        TableInfoHelper.initTableInfo(assistant, Elevator.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        elevatorMapper = mock(ElevatorMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        messageMapper = mock(MessageMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.selectList(any())).thenReturn(List.of());
        ChecklistService checklistService = new ChecklistService();
        checklistService.load();
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        UseUnit unit = new UseUnit();
        unit.unitName = "启元物业";
        when(useUnitMapper.selectById(any())).thenReturn(unit);
        TenantSnapshotService snapshots = new TenantSnapshotService(useUnitMapper,
                mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        service = new DispatchService(elevatorMapper, orderMapper, messageMapper,
                employeeMapper, checklistService, new OrderNoIssuer(orderMapper), snapshots);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectList(any())).thenReturn(List.of());
    }

    /** 派单前会 SELECT ... FOR UPDATE 取锁；mock 按扫描顺序返回同一批电梯 */
    private void givenElevators(List<Elevator> list) {
        rows = list;
        lockCursor.set(0);
        when(elevatorMapper.selectList(any())).thenReturn(list);
        when(elevatorMapper.selectOne(any())).thenAnswer(inv -> {
            int i = lockCursor.getAndIncrement();
            return rows.isEmpty() ? null : rows.get(Math.min(i, rows.size() - 1));
        });
    }

    private Elevator dueElevator(String id) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = "EM-" + id;
        el.elevatorName = "电梯" + id;
        el.useUnitId = "uu_" + id;
        el.category = "曳引与强制驱动电梯";
        el.workTypeCode = "HM";
        el.intervalDays = 15;
        el.regionId = "rg_1";
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
            Elevator source = elevators.stream().filter(e -> e.id.equals(o.elevatorId))
                    .findFirst().orElseThrow();
            assertEquals(source.useUnitId, o.useUnitId, "工单应冻结派单时电梯的 useUnitId");
            assertEquals("启元物业", o.useUnitName, "工单应冻结解析后的单位名");
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

    /** 累加式升级基线 = 首次维保日期（用户确认 2026-11-06）：
     *  只做过半月保、从未做过季/半年/年保的电梯，应按最早一次 DONE 签退起算，而非最近一次任意维保。 */
    @Test
    void neverDoneTypeUpgradesFromFirstMaintenanceDate() {
        Elevator el = dueElevator("el_first");
        el.lastMaintenanceAt = TimeUtil.now().minusDays(400);
        givenElevators(List.of(el));

        LocalDateTime earliest = TimeUtil.now().minusDays(400);
        LocalDateTime latest = TimeUtil.now().minusDays(15);
        when(orderMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<WorkOrder> w = inv.getArgument(0);
            boolean byType = w.getSqlSegment().contains("work_type_code");
            if (byType) {
                return List.of(); // OY/SM/TM 从未执行
            }
            WorkOrder old = new WorkOrder();
            old.workTypeCode = "HM";
            old.checkoutTime = earliest;
            WorkOrder recent = new WorkOrder();
            recent.workTypeCode = "HM";
            recent.checkoutTime = latest;
            return List.of(old, recent);
        });

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        assertEquals("OY", created.get(0).workTypeCode,
                "距首次维保 ≥365 天且从未做过年保，应按首次维保日期升级年保");
    }

    @Test
    void activeOrderSkipsDispatch() {
        Elevator el = dueElevator("el_a");
        givenElevators(List.of(el));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.ensureDueOrders().size());
    }

    /** 区域抢单池（D2/M4）：自动派单不带人——主/配合人四字段一律为空、落 regionId 快照、PENDING=待接单 */
    @Test
    void autoDispatchCreatesUnassignedPoolOrder() {
        Elevator el = dueElevator("el_pool"); // 档案绑定字段随意填（派单链路不再读取）
        el.assistantName = "李强";
        el.assistantPlatformId = "990002";
        givenElevators(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size());
        WorkOrder o = created.get(0);
        assertEquals("PENDING", o.status);
        assertNull(o.workerName, "池化工单不得写主维保（签到认领时写入）");
        assertNull(o.workerPlatformId, "池化工单不得写主维保平台ID");
        assertNull(o.assistantName, "池化工单不得写配合人（主维保签到后手动挑选）");
        assertNull(o.assistantPlatformId, "池化工单不得写配合人平台ID");
        assertEquals("rg_1", o.regionId, "工单应落电梯 regionId 快照（池查询不 join 电梯表）");
    }

    /** 区域抢单池（D2）：电梯未绑定主维保不再跳过派单——池化工单天然不需要预绑定 */
    @Test
    void autoDispatchNoLongerSkipsUnboundElevator() {
        Elevator el = dueElevator("el_nb");
        el.workerName = null;
        el.workerPlatformId = null;
        givenElevators(List.of(el));

        List<WorkOrder> created = service.ensureDueOrders();
        assertEquals(1, created.size(), "未绑定主维保的电梯仍应派单（发布到区域池）");
        assertNull(created.get(0).workerName);
        assertEquals("rg_1", created.get(0).regionId);
    }

    /** 手动派单 = 立即发布到区域池：无绑定 422 守卫，工单同样不带人 */
    @Test
    void dispatchNowPublishesToPool() {
        Elevator el = dueElevator("el_manual");
        el.workerName = null;
        el.workerPlatformId = null;
        el.regionId = "rg_9";
        when(elevatorMapper.selectById("el_manual")).thenReturn(el);

        WorkOrder o = service.dispatchNow("el_manual");
        assertEquals("PENDING", o.status);
        assertNull(o.workerName, "手动派单同样不带人（发布到区域池）");
        assertNull(o.workerPlatformId);
        assertNull(o.assistantName);
        assertNull(o.assistantPlatformId);
        assertEquals("rg_9", o.regionId, "手动派单也应落电梯 regionId 快照");
        assertEquals(Boolean.FALSE, o.autoDispatched);
        assertEquals(Boolean.TRUE, o.duoRequired, "所有派单入口创建的新单都必须双人");
        verify(orderMapper, times(1)).insert(any(WorkOrder.class));
    }

    /** 到期投影与派单同一基线：区域覆盖提示 regionStaffed、类型升级、超期判定（2026-11-21 维保计划页口径） */
    @Test
    void dueProjectionMatchesDispatchBaseline() {
        Elevator staffed = dueElevator("el_p1");
        Elevator unstaffed = dueElevator("el_p2");
        unstaffed.regionId = "rg_empty"; // 该区域无持平台ID的在职维保员
        givenElevators(List.of(staffed, unstaffed));
        // rg_1 有一名持平台ID的在职维保员；另一名同区域但无平台ID/已停用，不计入
        Employee keeper = new Employee();
        keeper.regionId = "rg_1";
        keeper.platformId = "pid_1";
        keeper.enabled = Boolean.TRUE;
        Employee noPlatform = new Employee();
        noPlatform.regionId = "rg_1";
        Employee disabled = new Employee();
        disabled.regionId = "rg_1";
        disabled.platformId = "pid_2";
        disabled.enabled = Boolean.FALSE;
        when(employeeMapper.selectList(any())).thenReturn(List.of(keeper, noPlatform, disabled));

        java.util.List<java.util.Map<String, Object>> rows = service.dueProjection();
        assertEquals(2, rows.size());
        java.util.Map<String, Object> r1 = rows.stream()
                .filter(r -> "el_p1".equals(r.get("elevatorId"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, r1.get("regionStaffed"), "rg_1 有持平台ID在职维保员，区域覆盖应为有人");
        assertEquals("rg_1", r1.get("regionId"));
        assertEquals("HM", r1.get("workTypeCode"));
        // 基线 16 天前、间隔 15：到期日 = 昨天 → 超期 1 天
        assertEquals("OVERDUE", r1.get("status"));
        assertEquals(-1L, r1.get("daysToDue"));
        java.util.Map<String, Object> r2 = rows.stream()
                .filter(r -> "el_p2".equals(r.get("elevatorId"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, r2.get("regionStaffed"), "rg_empty 无人持平台ID，前端应标「区域无人」");
    }

    @Test
    void notYetDueSkipsDispatch() {
        Elevator el = dueElevator("el_n");
        el.lastMaintenanceAt = TimeUtil.now().minusDays(5); // 15 天周期未到"到期前一天"
        givenElevators(List.of(el));

        assertEquals(0, service.ensureDueOrders().size());
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
        assertEquals(true, planDay.compareTo(TimeUtil.date(TimeUtil.now())) >= 0);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void locksElevatorRowBeforeCreatingOrder() {
        Elevator el = dueElevator("el_lock");
        givenElevators(List.of(el));

        assertEquals(1, service.ensureDueOrders().size());

        ArgumentCaptor<LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(elevatorMapper).selectOne(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().toUpperCase().contains("FOR UPDATE"),
                "派单创建前必须对电梯行加锁，否则并发跑批会为同一台电梯重复派单");
    }
}
