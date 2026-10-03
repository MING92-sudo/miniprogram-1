package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DemoDataSeeder;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 演示种子数据一致性：电梯绑定 / 工单 / 维保记录上的作业人员 platformId
 * 必须来自人员档案（employee 种子），否则班组数据权限（V7）按 platformId 过滤后
 * 作业人员登录看不到自己的工单，签到→清单→签退整条链路走不通。
 * 同理，班组长 group_name 缺失会让 sameGroup() 只返回自己 → 组长视角工单恒为 0（V7 无法验收）。
 */
class DemoDataSeederTest {

    private EmployeeMapper employeeMapper;
    private ElevatorMapper elevatorMapper;
    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper recordMapper;
    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() throws Exception {
        employeeMapper = mock(EmployeeMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        ChecklistService checklistService = new ChecklistService();
        checklistService.load();
        AppProperties props = new AppProperties();
        props.setSeedDemoData(true);
        when(employeeMapper.selectCount(any())).thenReturn(0L);
        seeder = new DemoDataSeeder(props, mock(CompanyMapper.class), employeeMapper,
                mock(UseUnitMapper.class), elevatorMapper, orderMapper, recordMapper,
                mock(MessageMapper.class), mock(RescueMapper.class), mock(FaultMapper.class),
                mock(DrillMapper.class), mock(InspectRecordMapper.class), mock(KnowledgeMapper.class),
                checklistService, mock(WorkOrderService.class));
    }

    @Test
    void seededPlatformIdsAllBelongToSeededEmployees() throws Exception {
        seeder.run(null);

        ArgumentCaptor<Employee> employees = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper, atLeastOnce()).insert(employees.capture());
        Set<String> known = employees.getAllValues().stream()
                .map(e -> e.platformId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        assertFalse(known.isEmpty(), "人员档案应至少播种一个平台人员ID");

        List<String> used = new ArrayList<>();
        ArgumentCaptor<Elevator> elevators = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper, atLeastOnce()).insert(elevators.capture());
        elevators.getAllValues().forEach(el -> {
            collect(used, el.workerPlatformId);
            collect(used, el.assistantPlatformId);
        });

        ArgumentCaptor<WorkOrder> orders = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper, atLeastOnce()).insert(orders.capture());
        orders.getAllValues().forEach(o -> {
            collect(used, o.workerPlatformId);
            collect(used, o.assistantPlatformId);
        });

        ArgumentCaptor<MaintainRecord> records = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(recordMapper, atLeastOnce()).insert(records.capture());
        records.getAllValues().forEach(r -> {
            collect(used, r.workerPlatformId);
            collect(used, r.assistantPlatformId);
        });

        assertFalse(used.isEmpty(), "电梯/工单/记录应带作业人员平台ID，否则用例形同虚设");
        Set<String> unknown = new HashSet<>(used);
        unknown.removeAll(known);
        assertTrue(unknown.isEmpty(), "以下 platformId 不属于任何人员档案，作业人员将看不到对应工单：" + unknown);
    }

    /** 班组长（陈刚）可见范围 = 本班组全部人员的 platformId，必须覆盖种子里工单用到的作业人员 */
    @Test
    void seededLeaderGroupCoversAllSeededOrderWorkers() throws Exception {
        seeder.run(null);

        ArgumentCaptor<Employee> employees = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper, atLeastOnce()).insert(employees.capture());
        Employee leader = employees.getAllValues().stream()
                .filter(e -> "LEADER".equals(e.role)).findFirst().orElse(null);
        assertTrue(leader != null, "种子应含班组长账号（LEADER）");
        assertTrue(leader.groupName != null && !leader.groupName.isBlank(),
                "班组长缺 group_name：sameGroup() 只返回自己，V7 组长视角看不到任何工单");

        Set<String> groupPlatformIds = employees.getAllValues().stream()
                .filter(e -> leader.groupName.equals(e.groupName))
                .map(e -> e.platformId)
                .filter(pid -> pid != null && !pid.isBlank())
                .collect(Collectors.toSet());

        ArgumentCaptor<WorkOrder> orders = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper, atLeastOnce()).insert(orders.capture());
        List<String> used = new ArrayList<>();
        orders.getAllValues().forEach(o -> {
            collect(used, o.workerPlatformId);
            collect(used, o.assistantPlatformId);
        });
        assertFalse(used.isEmpty(), "种子工单应带作业人员平台ID，否则用例形同虚设");

        Set<String> invisible = new HashSet<>(used);
        invisible.removeAll(groupPlatformIds);
        assertTrue(invisible.isEmpty(), "班组长可见范围未覆盖以下工单作业人员：" + invisible);
    }

    private void collect(List<String> into, String platformId) {
        if (platformId != null && !platformId.isBlank()) {
            into.add(platformId);
        }
    }
}
