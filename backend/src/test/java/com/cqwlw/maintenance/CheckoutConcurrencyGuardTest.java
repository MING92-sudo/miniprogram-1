package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
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
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不同请求同时签退只能产生一个监管记录；复核退回整改只能产生本地版本。 */
class CheckoutConcurrencyGuardTest {

    private static final String EMP_ID = "emp_1";
    private static final String ORDER_ID = "wo_1";

    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper recordMapper;
    private PlatformReportService reportService;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        reportService = mock(PlatformReportService.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        UseUnit uu = new UseUnit();
        uu.unitPrincipal = "单位负责人";
        uu.unitPrincipalPhone = "13800000002";
        uu.elevatorAdminister = "安全管理员";
        uu.elevatorAdministerPhone = "13800000003";
        uu.emergencyPhone = "023-12345678";
        when(useUnitMapper.selectById(any())).thenReturn(uu);
        AppProperties props = new AppProperties();
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        service = new WorkOrderService(orderMapper, elevatorMapper, useUnitMapper,
                employeeMapper, companyMapper, recordMapper, mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class), reportService,
                new EmployeeScopeService(employeeMapper), new CheckinThresholdService(mock(SysParamMapper.class), props),
                fileStorage, TxTestSupport.capturingTx().template, props);

        Employee employee = new Employee();
        employee.id = EMP_ID;
        employee.name = "张三";
        employee.role = "WORKER";
        employee.platformId = "pid_1";
        employee.phone = "13800000001";
        when(employeeMapper.selectById(EMP_ID)).thenReturn(employee);
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee));
        Company company = new Company();
        company.workMenegerName = "维保经理";
        company.workMenegerPhone = "13800000004";
        when(companyMapper.selectList(any())).thenReturn(List.of(company));
        when(elevatorMapper.selectById("el_1")).thenReturn(completeElevator());
        when(reportService.attemptUpload(any())).thenReturn("REPORTED");
    }

    @Test
    void concurrentFirstCheckoutAllowsOnlyOneRecordAndOneUpload() {
        WorkOrder first = processingOrder(null);
        WorkOrder second = processingOrder(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(first, second);
        when(orderMapper.completeCheckout(anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1, 0);

        service.checkout(ORDER_ID, checkoutBody(), EMP_ID);
        BizException loser = assertThrows(BizException.class,
                () -> service.checkout(ORDER_ID, checkoutBody(), EMP_ID));

        assertEquals(1003, loser.getCode());
        verify(recordMapper, times(1)).insert(any(MaintainRecord.class));
        verify(reportService, times(1)).attemptUpload(any(MaintainRecord.class));
    }

    @Test
    void reworkCheckoutCreatesLocalVersionWithoutReportPayloadOrUpload() {
        WorkOrder rework = processingOrder("OR1");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(rework);
        when(orderMapper.completeCheckout(anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        MaintainRecord previous = new MaintainRecord();
        previous.id = "rec_1";
        previous.workOrderId = ORDER_ID;
        previous.originalRecordId = "OR1";
        previous.version = 1;
        when(recordMapper.selectOne(any())).thenReturn(previous);

        Map<String, Object> result = service.checkout(ORDER_ID, checkoutBody(), EMP_ID);

        assertEquals("OR1", result.get("originalRecordId"));
        ArgumentCaptor<MaintainRecord> captor = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(recordMapper).insert(captor.capture());
        MaintainRecord v2 = captor.getValue();
        assertEquals(2, v2.version);
        assertEquals("rec_1", v2.prevVersionId);
        assertEquals("OR1", v2.originalRecordId);
        assertEquals("DRAFT", v2.reportStatus);
        assertNull(v2.reportPayloadJson);
        verify(reportService, never()).attemptUpload(any(MaintainRecord.class));
    }

    @Test
    void postUploadWritesOnlyReportStatusAndDoesNotOverwriteReviewReturn() {
        WorkOrder order = processingOrder(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(orderMapper.completeCheckout(anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(reportService.attemptUpload(any())).thenAnswer(inv -> {
            MaintainRecord r = inv.getArgument(0);
            r.lifecycleStatus = "REFUSED_RETURNED";
            order.status = "PROCESSING";
            return "REPORTED";
        });

        service.checkout(ORDER_ID, checkoutBody(), EMP_ID);

        assertEquals("PROCESSING", order.status);
        ArgumentCaptor<MaintainRecord> captor = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals("REFUSED_RETURNED", captor.getValue().lifecycleStatus);
        verify(recordMapper, never()).updateById(any(MaintainRecord.class));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    private static WorkOrder processingOrder(String originalRecordId) {
        WorkOrder o = new WorkOrder();
        o.id = ORDER_ID;
        o.elevatorId = "el_1";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "张三";
        o.workerPlatformId = "pid_1";
        o.assistantName = "王五";
        o.assistantPlatformId = "pid_2";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checkinExtraJson = "{\"role\":\"PRINCIPAL\"}";
        o.originalRecordId = originalRecordId;
        o.checklistJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"曳引机\","
                + "\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\"}]";
        return o;
    }

    private static Map<String, Object> checkoutBody() {
        return Map.of("signatureUrl", "https://x/sign.png",
                "assistantSignatureUrl", "https://x/assistant-sign.png");
    }

    private static Elevator completeElevator() {
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorCode = "EM-001";
        el.deviceCode = "DT-CQ-2024-001";
        el.regCode = "TSCQ5001120001";
        el.insideNumber = "KT-01";
        el.factoryNumber = "SGL20131212-1";
        el.useUnitId = "uu_1";
        return el;
    }
}
