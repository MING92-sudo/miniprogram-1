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
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.RecordPdfService;
import com.cqwlw.maintenance.service.WorkOrderService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R5 必须附说明； */
class RecordRefuseCodeTest {

    private static final String RECORD_ID = "ur_1";
    private static final String TOKEN = "sg_tok";

    private MaintainRecordMapper recordMapper;
    private WorkOrderMapper orderMapper;
    private RecordLifecycleService service;
    private MaintainRecord record;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
        recordMapper = mock(MaintainRecordMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        service = new RecordLifecycleService(recordMapper, orderMapper,
                mock(EmployeeMapper.class), mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                mock(RecordPdfService.class), mock(FileStorageService.class), mock(SysConfigMapper.class));
        record = new MaintainRecord();
        record.id = RECORD_ID;
        record.shareToken = TOKEN;
        record.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        record.confirmStatus = "PENDING";
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(recordMapper.selectCount(any())).thenReturn(0L); // 无后续版本
        when(recordMapper.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void refuseWithCodePersistsCodeAndComposedReason() {
        Map<String, Object> out = service.refuseByToken(RECORD_ID, TOKEN, "R3", "机房照片看不清");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("REFUSED_RETURNED", record.lifecycleStatus);
        assertEquals("R3", record.refuseCode);
        assertEquals("现场照片不清晰或与实际不符：机房照片看不清", record.refuseReason);
    }

    @Test
    void refuseWithCodeOnlyUsesLabel() {
        service.refuseByToken(RECORD_ID, TOKEN, "R2", null);
        assertEquals("R2", record.refuseCode);
        assertEquals("维保项目漏项或未按单执行", record.refuseReason);
    }

    @Test
    void invalidCodeRejectedWithoutSideEffect() {
        BizException e = assertThrows(BizException.class,
                () -> service.refuseByToken(RECORD_ID, TOKEN, "R9", "x"));
        assertEquals(422, e.getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", record.lifecycleStatus);
    }

    @Test
    void r5RequiresDetail() {
        BizException e = assertThrows(BizException.class,
                () -> service.refuseByToken(RECORD_ID, TOKEN, "R5", " "));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("必须填写拒签说明"), e.getMessage());
        service.refuseByToken(RECORD_ID, TOKEN, "R5", "物业内部流程原因");
        assertEquals("R5", record.refuseCode);
        assertEquals("其他：物业内部流程原因", record.refuseReason);
    }

    /** 兼容期：旧客户端未传分类码 → 回退原「原因必填」，不写 refuse_code */
    @Test
    void legacyWithoutCodeFallsBackToReasonRequired() {
        assertThrows(BizException.class, () -> service.refuseByToken(RECORD_ID, TOKEN, ""));
        Map<String, Object> out = service.refuseByToken(RECORD_ID, TOKEN, " 记录与现场不一致 ");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertNull(record.refuseCode);
        assertEquals("记录与现场不一致", record.refuseReason);
    }

    @Test
    void clientRefusalReopensForRework() {
        record.workOrderId = "wo_1";
        record.originalRecordId = "OR1";
        record.version = 1;
        record.reportStatus = "REPORTED";
        WorkOrder order = reworkOrder();
        when(orderMapper.selectById("wo_1")).thenReturn(order);
        when(orderMapper.update(isNull(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WorkOrder> update =
                    inv.getArgument(1);
            String sqlSet = update.getSqlSet();
            if (sqlSet.contains("worker_signature_file_id")) {
                order.workerSignatureFileId = null;
                order.workerSignatureAt = null;
            }
            if (sqlSet.contains("assistant_signature_file_id")) {
                order.assistantSignatureFileId = null;
                order.assistantSignatureAt = null;
            }
            if (sqlSet.contains("status")) {
                order.status = "PROCESSING";
            }
            return 1;
        });

        service.refuseByToken(RECORD_ID, TOKEN, "R3", "照片不清晰");

        assertEquals("REFUSED_RETURNED", record.lifecycleStatus);
        assertEquals("REPORTED", record.reportStatus);
        assertEquals("PROCESSING", order.status);
        assertEquals("OR1", order.originalRecordId);
        assertNull(order.workerSignatureFileId);
        assertNull(order.workerSignatureAt);
        assertNull(order.assistantSignatureFileId);
        assertNull(order.assistantSignatureAt);

        PlatformReportService reports = mock(PlatformReportService.class);
        WorkOrderService checkout = checkoutService(order, reports);
        when(recordMapper.selectOne(any())).thenReturn(record);
        when(orderMapper.completeCheckout(anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        checkout.checkout("wo_1", Map.of(
                "signatureUrl", "https://x/new-worker-sign.png",
                "assistantSignatureUrl", "https://x/new-assistant-sign.png"), "emp_1");

        ArgumentCaptor<MaintainRecord> inserted = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(recordMapper).insert(inserted.capture());
        assertEquals(2, inserted.getValue().version);
        assertEquals(RECORD_ID, inserted.getValue().prevVersionId);
        assertEquals("OR1", inserted.getValue().originalRecordId);
        assertEquals("DRAFT", inserted.getValue().reportStatus);
        assertNull(inserted.getValue().reportPayloadJson);
        assertEquals("REFUSED_RETURNED", record.lifecycleStatus);
        verify(reports, never()).attemptUpload(any(MaintainRecord.class));
    }

    private WorkOrderService checkoutService(WorkOrder order, PlatformReportService reports) {
        EmployeeMapper employees = mock(EmployeeMapper.class);
        Employee employee = new Employee();
        employee.id = "emp_1";
        employee.name = "张三";
        employee.role = "WORKER";
        employee.platformId = "pid_1";
        employee.phone = "13800000001";
        when(employees.selectById("emp_1")).thenReturn(employee);
        when(employees.selectList(any())).thenReturn(List.of(employee));

        ElevatorMapper elevators = mock(ElevatorMapper.class);
        Elevator elevator = new Elevator();
        elevator.id = order.elevatorId;
        elevator.elevatorCode = "EM-001";
        elevator.deviceCode = "DT-CQ-2024-001";
        elevator.regCode = "TSCQ5001120001";
        elevator.insideNumber = "KT-01";
        elevator.factoryNumber = "SGL20131212-1";
        elevator.useUnitId = "uu_1";
        when(elevators.selectById(order.elevatorId)).thenReturn(elevator);

        UseUnitMapper units = mock(UseUnitMapper.class);
        UseUnit unit = new UseUnit();
        unit.unitPrincipal = "单位负责人";
        unit.unitPrincipalPhone = "13800000002";
        unit.elevatorAdminister = "安全管理员";
        unit.elevatorAdministerPhone = "13800000003";
        unit.emergencyPhone = "023-12345678";
        when(units.selectById("uu_1")).thenReturn(unit);
        CompanyMapper companies = mock(CompanyMapper.class);
        Company company = new Company();
        company.workMenegerName = "维保经理";
        company.workMenegerPhone = "13800000004";
        when(companies.selectList(any())).thenReturn(List.of(company));
        FileStorageService files = mock(FileStorageService.class);
        when(files.urlOf(any())).thenReturn("");
        AppProperties props = new AppProperties();
        WorkOrderService checkout = new WorkOrderService(orderMapper, elevators, units, employees, companies, recordMapper,
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class), reports, new EmployeeScopeService(employees),
                new CheckinThresholdService(mock(SysParamMapper.class), props), files,
                TxTestSupport.capturingTx().template, props);
        checkout.setRecordLifecycleService(service);
        return checkout;
    }

    private static WorkOrder reworkOrder() {
        WorkOrder order = new WorkOrder();
        order.id = "wo_1";
        order.elevatorId = "el_1";
        order.workTypeCode = "HM";
        order.status = "DONE";
        order.workerName = "张三";
        order.workerPlatformId = "pid_1";
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_2";
        order.checkinTime = TimeUtil.now().minusMinutes(40);
        order.checkinExtraJson = "{\"role\":\"PRINCIPAL\"}";
        order.originalRecordId = "OR1";
        order.workerSignatureFileId = "file_old_worker";
        order.workerSignatureAt = LocalDateTime.now().minusMinutes(10);
        order.assistantSignatureFileId = "file_old_assistant";
        order.assistantSignatureAt = LocalDateTime.now().minusMinutes(9);
        order.checklistJson = "[{\"id\":\"ci_A-1-1\",\"itemCode\":\"A-1-1\",\"name\":\"曳引机\","
                + "\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\"}]";
        return order;
    }
}
