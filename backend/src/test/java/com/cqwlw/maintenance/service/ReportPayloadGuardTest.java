package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.TxTestSupport;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
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

/**
 * 2.6 报文数据正确性护栏（批次1 B1/B2/B3）：
 * B1 联系人手机号必须按平台人员 ID 强关联，禁止按姓名/任意 WORKER 兜底（虚假数据上平台）；
 * B2 维保单位档案缺失时中断而非上报空负责人；
 * B3 签退本地落库必须走事务——记录落库失败回滚，工单不得独留 DONE。
 */
class ReportPayloadGuardTest {

    private static final String EMP_LI = "emp_li";
    private static final String PID_LI = "pid_li";

    private EmployeeMapper employeeMapper;
    private CompanyMapper companyMapper;
    private MaintainRecordMapper recordMapper;
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
        orderMapper = mock(WorkOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        reportService = mock(PlatformReportService.class);
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        tx = TxTestSupport.capturingTx();
        service = new WorkOrderService(orderMapper, elevatorMapper,
                mock(UseUnitMapper.class), employeeMapper, companyMapper, recordMapper,
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(ChecklistService.class),
                mock(DispatchService.class), reportService,
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                fileStorage, tx.template, new AppProperties());
    }

    private MaintainRecord record(String workerName, String workerPlatformId) {
        MaintainRecord r = new MaintainRecord();
        r.id = Ids.next("ur");
        r.workerName = workerName;
        r.workerPlatformId = workerPlatformId;
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

    /** B1：姓名与档案不一致时，手机号仍按 platformId 精确取本人，绝不取他人 */
    @Test
    void recorderPhoneFollowsPlatformIdNotName() {
        when(companyMapper.selectList(any())).thenReturn(List.of(new Company()));
        // 档案姓名（李四）≠ 报文 recorder（张三），仅 platformId 关联
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee("李四", PID_LI, "13800000001")));
        Map<String, Object> payload = service.buildReportPayload(record("张三", PID_LI), null, null);
        assertEquals("13800000001", payload.get("recorderPhone"));
        assertEquals("张三", payload.get("recorder"));
    }

    /** B1：platformId 查不到本人 → 中断上报（旧实现会兜底取任意 WORKER 手机号） */
    @Test
    void recorderPhoneMissingPlatformLinkInterrupts() {
        when(companyMapper.selectList(any())).thenReturn(List.of(new Company()));
        when(employeeMapper.selectList(any())).thenReturn(List.of());
        BizException e = assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张三", "pid_ghost"), null, null));
        assertEquals(422, e.getCode());
    }

    /** B2：company 表空 = 建档缺失，中断而非上报空负责人 */
    @Test
    void missingCompanyInterruptsReport() {
        when(companyMapper.selectList(any())).thenReturn(List.of());
        BizException e = assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张三", PID_LI), null, null));
        assertEquals(422, e.getCode());
    }

    /** B3：维保记录落库失败 → 事务回滚、不上传平台、工单相关写不生效 */
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
        o.assistantPlatformId = "";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checklistJson = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\",\"notInThisRun\":false}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        when(elevatorMapper.selectById("el_1")).thenReturn(new Elevator());
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee("李四", PID_LI, "13800000001"));
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee("李四", PID_LI, "13800000001")));
        when(companyMapper.selectList(any())).thenReturn(List.of(new Company()));
        when(recordMapper.insert(any(MaintainRecord.class))).thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.checkout("wo_1", Map.of(), EMP_LI));
        assertTrue(tx.rolledBack, "记录落库失败必须回滚事务（B3）");
        assertFalse(tx.committed, "失败路径不得提交（B3）");
        verify(reportService, never()).attemptUpload(any());
    }
}
