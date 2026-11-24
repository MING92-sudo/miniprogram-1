package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderListSqlPageTest {

    private WorkOrderMapper orders;
    private ElevatorMapper elevators;
    private RegionMapper regions;
    private EmployeeMapper employees;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkOrder.class);
        orders = mock(WorkOrderMapper.class);
        elevators = mock(ElevatorMapper.class);
        regions = mock(RegionMapper.class);
        employees = mock(EmployeeMapper.class);
        Employee admin = new Employee();
        admin.id = "admin";
        admin.role = "ADMIN";
        when(employees.selectById("admin")).thenReturn(admin);
        service = new WorkOrderService(orders, elevators, mock(UseUnitMapper.class), employees,
                mock(CompanyMapper.class), mock(MaintainRecordMapper.class), mock(FaultMapper.class),
                mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class), mock(MessageMapper.class),
                mock(ChecklistService.class), mock(PlatformReportService.class), new EmployeeScopeService(employees),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                mock(FileStorageService.class), TxTestSupport.noopTx(), new AppProperties());
        service.setRegionMapper(regions);
    }

    @Test
    void usesSqlPageAndBatchLoadsOnlyCurrentPageReferences() {
        WorkOrder first = order("wo_1", "el_1");
        WorkOrder second = order("wo_2", "el_2");
        Page<WorkOrder> page = new Page<>(2, 2);
        page.setRecords(List.of(first, second));
        page.setTotal(11);
        when(orders.selectPage(any(), any())).thenReturn(page);
        when(elevators.selectList(any())).thenReturn(List.of(elevator("el_1"), elevator("el_2")));
        Region region = new Region();
        region.id = "r1";
        region.name = "渝北";
        first.regionId = "r1";
        when(regions.selectList(any())).thenReturn(List.of(region));

        Map<String, Object> out = service.listOrders(Map.of("page", "2", "size", "2"), "admin");

        assertEquals(11, out.get("total"));
        assertEquals(2, ((List<?>) out.get("list")).size());
        verify(orders).selectPage(any(), any());
        verify(orders, never()).selectList(any());
        verify(elevators, times(1)).selectList(any());
        verify(regions, times(1)).selectList(any());
        verify(elevators, never()).selectById(any());
        verify(regions, never()).selectById(any());
    }

    @Test
    void emptyPageDoesNotLoadReferences() {
        Page<WorkOrder> page = new Page<>(3, 20);
        page.setRecords(List.of());
        page.setTotal(2);
        when(orders.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> out = service.listOrders(Map.of("page", "3"), "admin");

        assertEquals(2, out.get("total"));
        verify(elevators, never()).selectList(any());
        verify(regions, never()).selectList(any());
    }

    @Test
    void keywordKeepsElevatorFieldsAndOrderNoMatching() {
        WorkOrder order = order("wo_1", "el_1");
        Page<WorkOrder> page = new Page<>(1, 20);
        page.setRecords(List.of(order));
        page.setTotal(1);
        when(orders.selectPage(any(), any())).thenReturn(page);
        when(elevators.selectList(any())).thenReturn(List.of(elevator("el_1")), List.of(elevator("el_1")));

        Map<String, Object> out = service.listOrders(Map.of("keyword", "REG-9"), "admin");

        assertEquals(1, out.get("total"));
        verify(orders).selectPage(any(), any());
        verify(elevators, times(2)).selectList(any());
    }

    @Test
    void workerListScopeIncludesPoolOrderWithNullWorkerAndRegion() {
        Employee worker = new Employee();
        worker.id = "worker";
        worker.role = "WORKER";
        worker.platformId = "pid_worker";
        when(employees.selectById("worker")).thenReturn(worker);
        when(employees.selectList(any())).thenReturn(List.of());
        AtomicReference<String> sql = new AtomicReference<>();
        when(orders.selectPage(any(), any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<WorkOrder> wrapper = invocation.getArgument(1);
            sql.set(wrapper.getSqlSegment());
            Page<WorkOrder> page = new Page<>(1, 20);
            page.setRecords(List.of());
            return page;
        });

        service.listOrders(Map.of(), "worker");

        assertTrue(sql.get().contains("}) OR ((worker_platform_id IS NULL"), sql.get());
        assertTrue(sql.get().contains("region_id IS NULL"), sql.get());
    }

    @Test
    void likeWildcardsAreEscapedAsLiteralCharacters() {
        assertEquals("\\%\\_\\\\", WorkOrderService.escapeLikeLiteral("%_\\"));
    }

    private static WorkOrder order(String id, String elevatorId) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = elevatorId;
        o.status = "PENDING";
        return o;
    }

    private static Elevator elevator(String id) {
        Elevator e = new Elevator();
        e.id = id;
        e.elevatorCode = "EM-" + id;
        e.elevatorName = "测试梯";
        e.regCode = "REG-9";
        return e;
    }
}
