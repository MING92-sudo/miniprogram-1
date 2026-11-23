package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
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
import com.cqwlw.maintenance.service.PhoneMutexService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作业前手机号互斥门禁：冲突时 checkin 抛 1002 + conflicts[]，且不落库、不改状态、不消费动态码（无副作用）。 */
class WorkOrderPhoneMutexGateTest {

    private static final String ORDER = "wo_mutex";
    private static final String EMP = "emp_wei";
    private static final String PID = "pid_wei";
    private static final String ELEVATOR = "el_mutex";
    private static final String MANAGER_PHONE = "13700000001";

    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private CompanyMapper companyMapper;
    private WorkOrderService service;
    private WorkOrder order;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
        orderMapper = mock(WorkOrderMapper.class);
        when(orderMapper.update(any(), any())).thenReturn(1);
        elevatorMapper = mock(ElevatorMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        companyMapper = mock(CompanyMapper.class);
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");

        service = new WorkOrderService(orderMapper, elevatorMapper, useUnitMapper,
                employeeMapper, companyMapper, mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class),
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                mock(FileStorageService.class), TxTestSupport.noopTx(), props);

        // 真实互斥服务注入（生产 Spring 必注入；此处显式 setter 注入模拟）
        service.setPhoneMutexService(new PhoneMutexService(companyMapper, useUnitMapper, employeeMapper));

        order = new WorkOrder();
        order.id = ORDER;
        order.elevatorId = ELEVATOR;
        order.status = "PENDING";
        order.workerPlatformId = PID;
        when(orderMapper.selectById(ORDER)).thenReturn(order);

        Elevator el = new Elevator();
        el.id = ELEVATOR;
        el.lat = BigDecimal.valueOf(29.6d);
        el.lng = BigDecimal.valueOf(106.5d);
        when(elevatorMapper.selectById(ELEVATOR)).thenReturn(el);

        Employee emp = new Employee();
        emp.id = EMP;
        emp.name = "张伟";
        emp.role = "WORKER";
        emp.platformId = PID;
        when(employeeMapper.selectById(EMP)).thenReturn(emp);

        // 维保人员1（工单 platformId 反查）手机号 = 维保经理手机号 → 冲突
        Employee worker = new Employee();
        worker.platformId = PID;
        worker.name = "张伟";
        worker.phone = MANAGER_PHONE;
        worker.role = "WORKER";
        when(employeeMapper.selectList(any())).thenReturn(List.of(worker));

        Company c = new Company();
        c.name = "维保单位";
        c.workMenegerPhone = MANAGER_PHONE;
        when(companyMapper.selectList(any())).thenReturn(List.of(c));
    }

    private static Map<String, Object> body() {
        Map<String, Object> b = new HashMap<>();
        b.put("role", "PRINCIPAL");
        b.put("latitude", "29.6001");
        b.put("longitude", "106.5001");
        b.put("photoFileId", "f_1");
        return b;
    }

    @Test
    void checkinBlocked1002WhenWorkerEqualsManager() {
        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(), EMP));
        assertEquals(1002, e.getCode(), "维保人员与维保经理同号 → 作业前拦截 1002");
        assertEquals("PENDING", order.status, "拦截后不得改状态（无副作用）");
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void checkinPassesWhenNoMutexConflict() {
        // 改成不冲突的经理手机号 → 签到通过
        Company c = new Company();
        c.name = "维保单位";
        c.workMenegerPhone = "13799999999";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));

        Map<String, Object> r = service.checkin(ORDER, body(), EMP);
        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals("PROCESSING", order.status);
    }
}
