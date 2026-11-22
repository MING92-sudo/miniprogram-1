package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.TxTestSupport;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportPayloadGuardTest {

    private static final String EMP_LI = "emp_li";
    private static final String PID_LI = "pid_li";

    private EmployeeMapper employeeMapper;
    private CompanyMapper companyMapper;
    private MaintainRecordMapper recordMapper;
    private UseUnitMapper useUnitMapper;
    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private PlatformReportService reportService;
    private WorkOrderService service;
    private TxTestSupport.CapturingTx tx;

    @BeforeEach
    void setUp() {
        employeeMapper = mock(EmployeeMapper.class);
        companyMapper = mock(CompanyMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        when(useUnitMapper.selectById(any())).thenReturn(completeUseUnit());
        orderMapper = mock(WorkOrderMapper.class);
        when(orderMapper.completeCheckout(any(), any(), any(), any(), any(), any())).thenReturn(1);
        elevatorMapper = mock(ElevatorMapper.class);
        reportService = mock(PlatformReportService.class);
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        tx = TxTestSupport.capturingTx();
        service = new WorkOrderService(orderMapper, elevatorMapper,
                useUnitMapper, employeeMapper, companyMapper, recordMapper,
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                reportService,
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                fileStorage, tx.template, new AppProperties());
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

    private static UseUnit completeUseUnit() {
        UseUnit uu = new UseUnit();
        uu.id = "uu_1";
        uu.unitPrincipal = "单位负责人";
        uu.unitPrincipalPhone = "13800000002";
        uu.elevatorAdminister = "安全管理员";
        uu.elevatorAdministerPhone = "13800000003";
        uu.emergencyPhone = "023-12345678";
        return uu;
    }

    private static Company completeCompany() {
        Company c = new Company();
        c.id = "co_1";
        c.name = "维保单位";
        c.workMenegerName = "维保经理";
        c.workMenegerPhone = "13800000004";
        return c;
    }

    private MaintainRecord record(String workerName, String workerPlatformId) {
        MaintainRecord r = new MaintainRecord();
        r.id = Ids.next("ur");
        r.workerName = workerName;
        r.workerPlatformId = workerPlatformId;
        r.assistantPlatformId = "pid_assistant";
        r.elevatorCode = "EM-001";
        r.checkinTime = LocalDateTime.of(2026, 11, 22, 8, 0);
        r.checkoutTime = LocalDateTime.of(2026, 11, 22, 9, 0);
        r.workTypeCode = "HM";
        r.originalRecordId = "1948179075784507801";
        r.problemCodesJson = "[\"S0\"]";
        r.nextMaintenanceDate = java.time.LocalDate.of(2026, 12, 7);
        return r;
    }

    private Employee employee(String name, String platformId, String phone) {
        Employee e = new Employee();
        e.name = name;
        e.role = "WORKER";
        e.platformId = platformId;
        e.phone = phone;
        return e;
    }

    /** 姓名与档案不一致时，手机号仍按 platformId 精确取本人，绝不取他人 */
    @Test
    void recorderPhoneFollowsPlatformIdNotName() {
        when(companyMapper.selectList(any())).thenReturn(List.of(completeCompany()));
        // 档案姓名（李四）≠ 报文 recorder（张三），仅 platformId 关联
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee("李四", PID_LI, "13800000001")));
        Map<String, Object> payload = service.buildReportPayload(record("张三", PID_LI), completeElevator(), completeUseUnit());
        assertEquals("13800000001", payload.get("recorderPhone"));
        assertEquals("张三", payload.get("recorder"));
    }

    /** 平台人员 ID 必须唯一，多条本地档案不得取首条冒充本人。 */
    @Test
    void duplicateWorkerPlatformIdInterruptsReport() {
        when(companyMapper.selectList(any())).thenReturn(List.of(completeCompany()));
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                employee("李四", PID_LI, "13800000001"),
                employee("李四（重复）", PID_LI, "13800000009")));

        BizException e = assertThrows(BizException.class, () -> service.buildReportPayload(
                record("张三", PID_LI), completeElevator(), completeUseUnit()));

        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("唯一"), e.getMessage());
    }

    /** platformId 查不到本人 → 中断上报 */
    @Test
    void recorderPhoneMissingPlatformLinkInterrupts() {
        when(companyMapper.selectList(any())).thenReturn(List.of(new Company()));
        when(employeeMapper.selectList(any())).thenReturn(List.of());
        BizException e = assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张三", "pid_ghost"), null, null));
        assertEquals(422, e.getCode());
    }

    @Test
    void missingCompanyInterruptsReport() {
        when(companyMapper.selectList(any())).thenReturn(List.of());
        BizException e = assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张三", PID_LI), null, null));
        assertEquals(422, e.getCode());
    }

    /** 签退签名服务端兜底：主维保签名缺失 → 422，且不落任何库（前端校验的双保险） */
    @Test
    void checkoutRequiresWorkerSignature() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.elevatorId = "el_1";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "张三";
        o.workerPlatformId = PID_LI;
        o.assistantPlatformId = "";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checklistJson = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\",\"notInThisRun\":false}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        when(elevatorMapper.selectById("el_1")).thenReturn(completeElevator());
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee("李四", PID_LI, "13800000001"));

        BizException e = assertThrows(BizException.class,
                () -> service.checkout("wo_1", Map.of(), EMP_LI));
        assertEquals(422, e.getCode());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    /** 设备信息档案必传项缺失（此处缺设备代码）→ 422 拒绝签退：不改工单、不落记录、不上报 */
    @Test
    void checkoutBlockedWhenDeviceProfileIncomplete() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.elevatorId = "el_1";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "张三";
        o.workerPlatformId = PID_LI;
        o.assistantPlatformId = "";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checklistJson = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\",\"notInThisRun\":false}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        Elevator el = completeElevator();
        el.deviceCode = "";
        el.factoryNumber = null;
        when(elevatorMapper.selectById("el_1")).thenReturn(el);
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee("李四", PID_LI, "13800000001"));

        BizException e = assertThrows(BizException.class,
                () -> service.checkout("wo_1", Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("设备代码"), e.getMessage());
        assertTrue(e.getMessage().contains("出厂编号"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
        verify(reportService, never()).attemptUpload(any());
    }

    @Test
    void checkoutRecordInsertFailureRollsBack() {
        AppProperties props = new AppProperties();
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.elevatorId = "el_1";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "张三";
        o.workerPlatformId = PID_LI;
        o.assistantName = "王五";
        o.assistantPlatformId = "pid_assistant";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checklistJson = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\",\"notInThisRun\":false}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        when(elevatorMapper.selectById("el_1")).thenReturn(completeElevator());
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee("李四", PID_LI, "13800000001"));
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee("李四", PID_LI, "13800000001")));
        when(companyMapper.selectList(any())).thenReturn(List.of(completeCompany()));
        when(recordMapper.insert(any(MaintainRecord.class))).thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.checkout("wo_1", Map.of(
                        "signatureUrl", "https://x/sign.png",
                        "assistantSignatureUrl", "https://x/assistant-sign.png"), EMP_LI));
        assertTrue(tx.rolledBack, "记录落库失败必须回滚事务（B3）");
        assertFalse(tx.committed, "失败路径不得提交（B3）");
        verify(reportService, never()).attemptUpload(any());
    }
}
