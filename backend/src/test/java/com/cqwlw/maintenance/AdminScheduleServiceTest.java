package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainPlan;
import com.cqwlw.maintenance.entity.PlanDelay;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainPlanMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.PlanDelayMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminScheduleService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.MigrationIssueService;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.service.TenantSnapshotService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划调度与延期审批（docs/04 A.5，docs/09 二期）：
 * 指派校验矩阵 1004/1007（platform_id、证件有效期、排班互斥 docs/01 §3.2.4 ④、一梯一单）；
 * 延期审批改期并回写工单；platformDateSynced 固化提示；转派仅 PENDING。
 */
class AdminScheduleServiceTest {

    private MaintainPlanMapper planMapper;
    private PlanDelayMapper delayMapper;
    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private EmployeeMapper employeeMapper;
    private CompanyMapper companyMapper;
    private MaintainRecordMapper recordMapper;
    private AdminScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        planMapper = mock(MaintainPlanMapper.class);
        delayMapper = mock(PlanDelayMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        UseUnit unit = new UseUnit();
        unit.unitName = "启元物业";
        when(useUnitMapper.selectById(any())).thenReturn(unit);
        TenantSnapshotService snapshots = new TenantSnapshotService(useUnitMapper,
                mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        companyMapper = mock(CompanyMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        ChecklistService checklistService = new ChecklistService();
        checklistService.load();
        service = new AdminScheduleService(planMapper, delayMapper, orderMapper, elevatorMapper,
                employeeMapper, useUnitMapper, companyMapper, recordMapper, messageMapper,
                checklistService, new OrderNoIssuer(orderMapper), snapshots);

        Company c = new Company();
        c.id = "co_1";
        c.name = "维保单位";
        c.workMenegerPhone = "13700000000";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));
    }

    private Employee employee(String id, String name, String phone, String role,
                              String platformId, String workEndDate) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.phone = phone;
        e.role = role;
        e.platformId = platformId;
        e.workEndDate = workEndDate;
        return e;
    }

    private Elevator elevator(String id, String code) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = code;
        el.elevatorName = "电梯" + code;
        el.useUnitId = "uu_" + id;
        el.category = "曳引与强制驱动电梯";
        el.workTypeCode = "HM";
        el.intervalDays = 15;
        return el;
    }

    // ── 生成排班池 ──

    @Test
    void generateCreatesPlanForDueElevator() {
        Elevator a = elevator("el_A", "EM-A");
        when(elevatorMapper.selectList(any())).thenReturn(List.of(a));
        WorkOrder done = new WorkOrder();
        done.elevatorId = "el_A";
        done.status = "DONE";
        done.checkoutTime = TimeUtil.now().minusDays(20);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectList(any())).thenReturn(List.of(done));
        when(planMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> out = service.generatePlans(
                TimeUtil.date(TimeUtil.now().minusDays(10)),
                TimeUtil.date(TimeUtil.now().plusDays(30)), "");

        assertEquals(1, out.get("generated"));
        ArgumentCaptor<MaintainPlan> captor = ArgumentCaptor.forClass(MaintainPlan.class);
        verify(planMapper).insert(captor.capture());
        assertEquals("UNASSIGNED", captor.getValue().status);
        assertEquals(TimeUtil.now().minusDays(20).toLocalDate().plusDays(15), captor.getValue().planDate);
    }

    @Test
    void generateSkipsElevatorWithOpenOrder() {
        Elevator b = elevator("el_B", "EM-B");
        when(elevatorMapper.selectList(any())).thenReturn(List.of(b));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        Map<String, Object> out = service.generatePlans(
                TimeUtil.date(TimeUtil.now().minusDays(10)),
                TimeUtil.date(TimeUtil.now().plusDays(30)), "");

        assertEquals(0, out.get("generated"));
        List<?> skipped = (List<?>) out.get("skipped");
        assertEquals(1, skipped.size());
        assertTrue(String.valueOf(((Map<?, ?>) skipped.get(0)).get("reason")).contains("已有未完成工单"));
        verify(planMapper, never()).insert(any(MaintainPlan.class));
    }

    // ── 指派校验矩阵 ──

    private MaintainPlan plan() {
        MaintainPlan p = new MaintainPlan();
        p.id = "pl_1";
        p.elevatorId = "el_1";
        p.planDate = TimeUtil.now().toLocalDate().plusDays(3);
        p.workTypeCode = "HM";
        p.status = "UNASSIGNED";
        return p;
    }

    private void stubPlanAndElevator() {
        when(planMapper.selectById("pl_1")).thenReturn(plan());
        when(elevatorMapper.selectById("el_1")).thenReturn(elevator("el_1", "EM-1"));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void assignSuccessCreatesManualOrder() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "990001", "2027-11-05"));
        when(employeeMapper.selectById("emp_a")).thenReturn(
                employee("emp_a", "李强", "13800000004", "WORKER", "990003", "2028-02-13"));

        Map<String, Object> out = service.assign("pl_1", "emp_w", "emp_a",
                TimeUtil.date(TimeUtil.now().plusDays(3)));

        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().status);
        assertEquals(Boolean.FALSE, captor.getValue().autoDispatched);
        assertEquals(Boolean.TRUE, captor.getValue().duoRequired, "人工排班新单必须显式双人");
        assertEquals("张伟", captor.getValue().workerName);
        assertEquals("uu_el_1", captor.getValue().useUnitId, "排班工单应冻结电梯的 useUnitId");
        assertEquals("启元物业", captor.getValue().useUnitName, "排班工单应冻结解析后的单位名");
        assertEquals(TimeUtil.now().plusDays(3).toLocalDate() + "T09:00",
                captor.getValue().planTime.toString());
        ArgumentCaptor<MaintainPlan> planCaptor = ArgumentCaptor.forClass(MaintainPlan.class);
        verify(planMapper).updateById(planCaptor.capture());
        assertEquals("ASSIGNED", planCaptor.getValue().status);
        assertEquals(captor.getValue().id, planCaptor.getValue().orderId);
        assertEquals("张伟", out.get("workerName"));
    }

    @Test
    void assignRejectsMissingAssistantWithoutAnyWrite() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "990001", "2027-11-05"));

        BizException e = assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "", ""));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("配合人员"));
        verify(orderMapper, never()).insert(any(WorkOrder.class));
        verify(planMapper, never()).updateById(any(MaintainPlan.class));
    }

    @Test
    void assignRejectsMissingPlatformId1004() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "", "2027-11-05"));
        BizException e = assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "", ""));
        assertEquals(1004, e.getCode());
    }

    @Test
    void assignRejectsMutexWithAssistantAndManager1007() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "990001", "2027-11-05"));
        // 同手机号
        when(employeeMapper.selectById("emp_a")).thenReturn(
                employee("emp_a", "李强", "13800000001", "WORKER", "990003", "2028-02-13"));
        assertEquals(1007, assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "emp_a", "")).getCode());
        // 与维保经理同号
        when(employeeMapper.selectById("emp_a")).thenReturn(
                employee("emp_a", "李强", "13700000000", "WORKER", "990003", "2028-02-13"));
        assertEquals(1007, assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "emp_a", "")).getCode());
    }

    @Test
    void assignRejectsExpiredCertificate1007() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "990001", "2020-01-01"));
        assertEquals(1007, assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "", "")).getCode());
    }

    @Test
    void assignRejectsWhenElevatorBusy1007() {
        stubPlanAndElevator();
        when(employeeMapper.selectById("emp_w")).thenReturn(
                employee("emp_w", "张伟", "13800000001", "WORKER", "990001", "2027-11-05"));
        when(orderMapper.selectCount(any())).thenReturn(1L);
        assertEquals(1007, assertThrows(BizException.class,
                () -> service.assign("pl_1", "emp_w", "", "")).getCode());
    }

    // ── 延期与审批 ──

    @Test
    void delayApprovalMovesPlanAndOrderDate() {
        stubPlanAndElevator();
        MaintainPlan p = plan();
        p.status = "ASSIGNED";
        p.principalId = "emp_w";
        p.orderId = "wo_1";
        when(planMapper.selectById("pl_1")).thenReturn(p);
        WorkOrder order = new WorkOrder();
        order.id = "wo_1";
        order.status = "PENDING";
        order.planTime = TimeUtil.now().plusDays(3).toLocalDate().atTime(9, 0);
        when(orderMapper.selectById("wo_1")).thenReturn(order);
        when(recordMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> applied = service.applyDelay("pl_1", "电梯使用方检修配合",
                null, TimeUtil.date(TimeUtil.now().plusDays(10)), "emp_2");
        assertEquals("PENDING", applied.get("status"));
        assertEquals("POSTPONE_PENDING", p.status);

        ArgumentCaptor<PlanDelay> delayCaptor = ArgumentCaptor.forClass(PlanDelay.class);
        verify(delayMapper).insert(delayCaptor.capture());
        String delayId = delayCaptor.getValue().id;
        when(delayMapper.selectById(delayId)).thenReturn(delayCaptor.getValue());

        Map<String, Object> decided = service.decideDelay(delayId, true, "同意", "emp_admin");
        assertEquals("APPROVED", decided.get("status"));
        assertEquals(Boolean.TRUE, decided.get("platformDateSynced"));
        assertEquals(TimeUtil.now().plusDays(10).toLocalDate(), p.planDate);
        assertEquals(TimeUtil.now().plusDays(10).toLocalDate() + "T09:00", order.planTime.toString());
        assertEquals("ASSIGNED", p.status);
        verify(planMapper, times(2)).updateById(p);
    }

    @Test
    void platformFixedDateSurfacesWarningOnDecide() {
        stubPlanAndElevator();
        MaintainPlan p = plan();
        p.status = "ASSIGNED";
        p.principalId = "emp_w";
        p.orderId = "wo_1";
        when(planMapper.selectById("pl_1")).thenReturn(p);
        Elevator el = elevator("el_1", "EM-1");
        when(elevatorMapper.selectById("el_1")).thenReturn(el);
        WorkOrder order = new WorkOrder();
        order.id = "wo_1";
        order.status = "PENDING";
        order.planTime = TimeUtil.now().plusDays(3).toLocalDate().atTime(9, 0);
        when(orderMapper.selectById("wo_1")).thenReturn(order);
        when(recordMapper.selectCount(any())).thenReturn(1L);

        service.applyDelay("pl_1", "原因", null, TimeUtil.date(TimeUtil.now().plusDays(10)), "emp_2");
        ArgumentCaptor<PlanDelay> delayCaptor = ArgumentCaptor.forClass(PlanDelay.class);
        verify(delayMapper).insert(delayCaptor.capture());
        String delayId = delayCaptor.getValue().id;
        when(delayMapper.selectById(delayId)).thenReturn(delayCaptor.getValue());

        Map<String, Object> decided = service.decideDelay(delayId, true, "", "emp_admin");
        assertEquals(Boolean.FALSE, decided.get("platformDateSynced"));
        assertTrue(String.valueOf(decided.get("platformFixedNote")).contains("已固化"));
    }

}
