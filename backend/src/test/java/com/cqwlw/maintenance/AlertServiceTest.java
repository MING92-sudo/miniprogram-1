package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.AlertRecord;
import com.cqwlw.maintenance.entity.AlertRule;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.AlertRecordMapper;
import com.cqwlw.maintenance.mapper.AlertRuleMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AlertService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预警生成与规则（docs/01 §3.11 / docs/04 A.6）：
 * 年检/证件/维保超期/确认超时/自检五类可生成，幂等去重，规则可编辑启停。
 */
class AlertServiceTest {

    private AlertRuleMapper ruleMapper;
    private AlertRecordMapper recordMapper;
    private ElevatorMapper elevatorMapper;
    private EmployeeMapper employeeMapper;
    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper maintainRecordMapper;
    private InspectRecordMapper inspectMapper;
    private AlertService service;

    private AlertRule rule(String id, String type, int days) {
        AlertRule r = new AlertRule();
        r.id = id;
        r.type = type;
        r.name = type;
        r.advanceDays = days;
        r.target = "管理端";
        r.enabled = true;
        return r;
    }

    @BeforeEach
    void setUp() {
        ruleMapper = mock(AlertRuleMapper.class);
        recordMapper = mock(AlertRecordMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        maintainRecordMapper = mock(MaintainRecordMapper.class);
        inspectMapper = mock(InspectRecordMapper.class);
        service = new AlertService(ruleMapper, recordMapper, elevatorMapper,
                employeeMapper, orderMapper, maintainRecordMapper, inspectMapper);
        when(recordMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void generateCreatesYearCheckCertAndSelfInspectAlerts() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule("al_1", "YEAR_CHECK", 60),
                rule("al_3", "CERT_EXPIRE", 60),
                rule("al_8", "SELF_INSPECT", 30)));

        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorName = "世纪大厦 1#";
        el.nextCheckDate = LocalDate.now(TimeUtil.ZONE).plusDays(10);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(inspectMapper.selectCount(any())).thenReturn(0L);

        Employee e = new Employee();
        e.id = "emp_1";
        e.name = "张伟";
        e.workEndDate = TimeUtil.date(TimeUtil.now().plusDays(20));
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));

        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(maintainRecordMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> out = service.generate();
        // YEAR_CHECK + CERT_EXPIRE + SELF_INSPECT 各 1 条
        assertEquals(3, out.get("created"));
        verify(recordMapper, times(3)).insert(any(AlertRecord.class));
    }

    @Test
    void generateCreatesOverdueAndConfirmTimeoutAlerts() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule("al_4", "MAINT_OVERDUE", -1),
                rule("al_6", "CONFIRM_TIMEOUT", 1)));

        when(elevatorMapper.selectList(any())).thenReturn(List.of());
        when(employeeMapper.selectList(any())).thenReturn(List.of());

        WorkOrder overdue = new WorkOrder();
        overdue.id = "wo_1";
        overdue.orderNo = "WO-X";
        overdue.status = "PENDING";
        overdue.planTime = TimeUtil.now().minusDays(3);
        when(orderMapper.selectList(any())).thenReturn(List.of(overdue));

        MaintainRecord pending = new MaintainRecord();
        pending.elevatorName = "蓝湾国际 A 座货梯";
        pending.confirmStatus = "PENDING";
        pending.createdAt = TimeUtil.now().minusHours(30);
        when(maintainRecordMapper.selectList(any())).thenReturn(List.of(pending));

        Map<String, Object> out = service.generate();
        assertEquals(2, out.get("created"));
    }

    @Test
    void generateIsIdempotentWhenOpenAlertExists() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule("al_1", "YEAR_CHECK", 60)));
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorName = "世纪大厦 1#";
        el.nextCheckDate = LocalDate.now(TimeUtil.ZONE).plusDays(10);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(inspectMapper.selectCount(any())).thenReturn(0L);
        when(employeeMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(maintainRecordMapper.selectList(any())).thenReturn(List.of());
        when(recordMapper.selectCount(any())).thenReturn(1L); // 已存在 OPEN

        assertEquals(0, service.generate().get("created"));
        verify(recordMapper, times(0)).insert(any(AlertRecord.class));
    }

    @Test
    void updateRuleRejectsBadAdvanceDays() {
        AlertRule r = rule("al_1", "YEAR_CHECK", 60);
        when(ruleMapper.selectById("al_1")).thenReturn(r);
        assertThrows(com.cqwlw.maintenance.common.BizException.class,
                () -> service.updateRule("al_1", Map.of("advanceDays", "abc")));

        Map<String, Object> updated = service.updateRule("al_1", Map.of("advanceDays", 30, "enabled", false));
        assertEquals(30, updated.get("advanceDays"));
        assertEquals(Boolean.FALSE, updated.get("enabled"));
        assertTrue(r.enabled == null || !r.enabled);
    }
}
