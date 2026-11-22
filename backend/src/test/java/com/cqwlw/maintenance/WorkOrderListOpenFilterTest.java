package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkOrderListOpenFilterTest {

    private static final String EMP_ADMIN = "emp_admin";

    private WorkOrderService service;
    private WorkOrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        service = new WorkOrderService(orderMapper, mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                mock(FileStorageService.class), TxTestSupport.noopTx(), new AppProperties());

        Employee admin = new Employee();
        admin.id = EMP_ADMIN;
        admin.name = EMP_ADMIN;
        admin.role = "ADMIN";
        when(employeeMapper.selectById(EMP_ADMIN)).thenReturn(admin);

        WorkOrder future = order("wo_future", "PENDING");
        future.planTime = java.time.LocalDateTime.now().plusDays(10);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("wo_p", "PENDING"),
                order("wo_g", "PROCESSING"),
                order("wo_d", "DONE"),
                future));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkOrder> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(order("wo_p", "PENDING"), order("wo_g", "PROCESSING"), future));
        page.setTotal(3);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
    }

    /** OPEN = 未闭环：PENDING + PROCESSING 保留，DONE 排除 */
    @Test
    void openStatusExcludesDone() {
        Map<String, Object> res = service.listOrders(Map.of("status", "OPEN"), EMP_ADMIN);
        assertEquals(3, res.get("total"));
    }

    /** 精确状态过滤语义不变 */
    @Test
    void exactStatusFilterUnchanged() {
        when(orderMapper.selectPage(any(), any())).thenReturn(
                pageOf(List.of(order("wo_p", "PENDING"), order("wo_future", "PENDING")), 2),
                pageOf(List.of(order("wo_d", "DONE")), 1),
                pageOf(List.of(order("wo_p", "PENDING"), order("wo_g", "PROCESSING"),
                        order("wo_d", "DONE"), order("wo_future", "PENDING")), 4));
        assertEquals(2, service.listOrders(Map.of("status", "PENDING"), EMP_ADMIN).get("total"));
        assertEquals(1, service.listOrders(Map.of("status", "DONE"), EMP_ADMIN).get("total"));
        assertEquals(4, service.listOrders(Map.of(), EMP_ADMIN).get("total"));
        assertEquals(3, service.homeSummary(EMP_ADMIN).get("openOrders"));
    }

    private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkOrder> pageOf(
            List<WorkOrder> rows, int total) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkOrder> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(rows);
        page.setTotal(total);
        return page;
    }

    private static WorkOrder order(String id, String status) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.status = status;
        return o;
    }
}
