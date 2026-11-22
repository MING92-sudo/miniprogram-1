package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
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
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 签字严格各签各的：LEADER 不得代签或代签退。 */
class WorkOrderSignatureTest {

    private static final String GROUP = "维保一班";
    private static final String PID_ZHANG = "pid_zhang"; // 主维保
    private static final String PID_LI = "pid_li";       // 配合人员
    private static final String EMP_ZHANG = "emp_zhang";
    private static final String EMP_LI = "emp_li";
    private static final String EMP_CHEN = "emp_chen";   // 组长
    /** 双人工单：张伟主维保、李强配合，PROCESSING */
    private static final String DUAL_ORDER = "wo_dual";
    /** 张伟他人工单（李强非其主/配合，越权） */
    private static final String FOREIGN_ORDER = "wo_foreign";

    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class), mock(FaultMapper.class),
                mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()), mock(FileStorageService.class), TxTestSupport.noopTx(), new AppProperties());

        when(orderMapper.selectById(DUAL_ORDER)).thenReturn(dualOrder(DUAL_ORDER, PID_ZHANG, PID_LI, "PROCESSING"));
        when(orderMapper.selectById(FOREIGN_ORDER)).thenReturn(dualOrder(FOREIGN_ORDER, "pid_other", "pid_other2", "PROCESSING"));
        when(employeeMapper.selectById(EMP_ZHANG)).thenReturn(employee(EMP_ZHANG, "张伟", "WORKER", PID_ZHANG));
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee(EMP_LI, "李强", "WORKER", PID_LI));
        when(employeeMapper.selectById(EMP_CHEN)).thenReturn(employee(EMP_CHEN, "陈刚", "LEADER", "pid_chen"));
        // 组长按班组展开：本组 = 张伟 + 李强（EmployeeScopeService.sameGroup 走 selectList）
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                employee(EMP_ZHANG, "张伟", "WORKER", PID_ZHANG),
                employee(EMP_LI, "李强", "WORKER", PID_LI)));
    }

    @Test
    void workerCannotSignForeignOrder() {
        assertCode(1403, () -> service.signOrder(FOREIGN_ORDER, sign("PRINCIPAL", "f1"), EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void signRequiresProcessingStatus() {
        when(orderMapper.selectById(DUAL_ORDER)).thenReturn(dualOrder(DUAL_ORDER, PID_ZHANG, PID_LI, "PENDING"));
        assertCode(1003, () -> service.signOrder(DUAL_ORDER, sign("PRINCIPAL", "f1"), EMP_ZHANG));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void signRejectsInvalidRole() {
        assertCode(422, () -> service.signOrder(DUAL_ORDER, sign("BOSS", "f1"), EMP_ZHANG));
    }

    @Test
    void signRequiresSignatureFileId() {
        assertCode(422, () -> service.signOrder(DUAL_ORDER, sign("PRINCIPAL", ""), EMP_ZHANG));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void principalCanSignOwnRole() {
        service.signOrder(DUAL_ORDER, sign("PRINCIPAL", "file_worker"), EMP_ZHANG);
        WorkOrder captured = captureUpdate();
        assertEquals("file_worker", captured.workerSignatureFileId);
    }

    @Test
    void assistantCanSignOwnRole() {
        service.signOrder(DUAL_ORDER, sign("ASSISTANT", "file_assistant"), EMP_LI);
        WorkOrder captured = captureUpdate();
        assertEquals("file_assistant", captured.assistantSignatureFileId);
    }

    /** 严格各签各的：普通工人（主维保）不得签配合人员签字 */
    @Test
    void workerCannotSignOthersRole() {
        assertCode(1403, () -> service.signOrder(DUAL_ORDER, sign("ASSISTANT", "file_x"), EMP_ZHANG));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void leaderCannotUseServiceSideWriteBypasses() {
        assertCode(1403, () -> service.signOrder(DUAL_ORDER, sign("ASSISTANT", "file_by_leader"), EMP_CHEN));
        assertCode(1403, () -> service.checkout(DUAL_ORDER, Map.of(), EMP_CHEN));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    /** 签退仅主维保可操作：配合人员（李强）即使工单归属本人，也不得签退（防代签退 → 平台 2.6 虚假数据） */
    @Test
    void assistantCannotCheckout() {
        assertCode(1403, () -> service.checkout(DUAL_ORDER, Map.of(), EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    private WorkOrder captureUpdate() {
        ArgumentCaptor<WorkOrder> cap = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper).updateById(cap.capture());
        return cap.getValue();
    }

    private static Map<String, Object> sign(String role, String fileId) {
        return Map.of("role", role, "signatureFileId", fileId);
    }

    private static void assertCode(int code, Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        assertEquals(code, e.getCode());
    }

    private static WorkOrder dualOrder(String id, String workerPid, String assistantPid, String status) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.workTypeCode = "HM";
        o.status = status;
        o.workerName = "张伟";
        o.workerPlatformId = workerPid;
        o.assistantName = "李强";
        o.assistantPlatformId = assistantPid;
        o.checkinTime = TimeUtil.now().minusHours(2);
        o.checklistJson = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false}]";
        return o;
    }

    private static Employee employee(String id, String name, String role, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = role;
        e.platformId = platformId;
        e.groupName = GROUP;
        return e;
    }
}
