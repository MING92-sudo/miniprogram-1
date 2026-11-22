package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.Message;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首保签退遗留问题回写（Task 5 后半）：首保工单 handoverIssue=true 时，
 * 签退须把来源移交单标记遗留问题并通知安装部；无遗留问题不写、来源单缺失只记日志不阻断签退。
 */
class HandoverIssueClosureTest {

    private static final String EMP_LI = "emp_li";
    private static final String PID_LI = "pid_li";
    private static final String ORDER = "wo_first";
    private static final String HANDOVER = "ho_1";
    private static final String CHECKLIST =
            "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\"}]";

    private WorkOrderMapper orderMapper;
    private HandoverOrderMapper handoverMapper;
    private MessageMapper messageMapper;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        when(orderMapper.completeCheckout(any(), any(), any(), any(), any(), any())).thenReturn(1);
        handoverMapper = mock(HandoverOrderMapper.class);
        messageMapper = mock(MessageMapper.class);
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
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        props.setWorkDurationMinutes(30);
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        service = new WorkOrderService(orderMapper, elevatorMapper, useUnitMapper,
                employeeMapper, companyMapper, mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                handoverMapper, messageMapper, mock(ChecklistService.class),
                mock(PlatformReportService.class), new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                fileStorage, TxTestSupport.noopTx(), props);

        Employee li = new Employee();
        li.id = EMP_LI;
        li.name = "李强";
        li.role = "WORKER";
        li.platformId = PID_LI;
        li.phone = "13800000000";
        when(employeeMapper.selectById(EMP_LI)).thenReturn(li);
        when(employeeMapper.selectList(any())).thenReturn(List.of(li));
        when(orderMapper.selectById(ORDER)).thenReturn(order(true));
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorName = "1号楼客梯";
        el.elevatorCode = "EM-001";
        // 上传维保记录的 5 项必传项（docs/01 §3.4.1）：签退门禁要求齐备
        el.deviceCode = "DT-CQ-2024-001";
        el.regCode = "TSCQ5001120001";
        el.insideNumber = "KT-01";
        el.factoryNumber = "SGL20131212-1";
        el.useUnitId = "uu_1";
        when(elevatorMapper.selectById("el_1")).thenReturn(el);
        Company company = new Company();
        company.workMenegerName = "王经理";
        company.workMenegerPhone = "13900000000";
        when(companyMapper.selectList(isNull())).thenReturn(List.of(company));
    }

    /** 首保签退 handoverIssue=true → 回写来源移交单标记 + 消息中心通知安装部（含发起人） */
    @Test
    void checkoutWritesBackAndNotifies() {
        HandoverOrder ho = new HandoverOrder();
        ho.id = HANDOVER;
        ho.initiatorName = "赵安装";
        when(handoverMapper.selectById(HANDOVER)).thenReturn(ho);

        service.checkout(ORDER, Map.of("signatureUrl", "https://x/sign.png", "assistantSignatureUrl", "https://x/assistant-sign.png"), EMP_LI);

        ArgumentCaptor<HandoverOrder> hoCap = ArgumentCaptor.forClass(HandoverOrder.class);
        verify(handoverMapper).updateById(hoCap.capture());
        assertEquals(HANDOVER, hoCap.getValue().id);
        assertEquals(Boolean.TRUE, hoCap.getValue().handoverIssue, "来源移交单应标记遗留问题");

        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(msgCap.capture());
        assertTrue(msgCap.getValue().title.contains("遗留问题"), "应生成遗留问题通知");
        assertTrue(msgCap.getValue().content.contains("赵安装"), "通知应指明安装部发起人");
    }

    /** 无遗留问题（handoverIssue=false）不写移交单、不发通知 */
    @Test
    void noWriteBackWithoutIssue() {
        when(orderMapper.selectById(ORDER)).thenReturn(order(false));

        service.checkout(ORDER, Map.of("signatureUrl", "https://x/sign.png", "assistantSignatureUrl", "https://x/assistant-sign.png"), EMP_LI);

        verify(handoverMapper, never()).updateById(any(HandoverOrder.class));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    /** 来源移交单缺失 → 防御性跳过，签退本身仍然成功（不因内部回写失败阻断合规链） */
    @Test
    void missingHandoverDoesNotBreakCheckout() {
        when(handoverMapper.selectById(HANDOVER)).thenReturn(null);

        Map<String, Object> result = assertDoesNotThrow(
                () -> service.checkout(ORDER, Map.of("signatureUrl", "https://x/sign.png", "assistantSignatureUrl", "https://x/assistant-sign.png"), EMP_LI));

        assertEquals(ORDER, result.get("workOrderId"));
        verify(messageMapper, never()).insert(any(Message.class));
    }

    private static WorkOrder order(boolean handoverIssue) {
        WorkOrder o = new WorkOrder();
        o.id = ORDER;
        o.orderNo = "WO-20261112-0001";
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "李强";
        o.workerPlatformId = PID_LI;
        o.assistantName = "王五";
        o.assistantPlatformId = "pid_wang";
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checklistJson = CHECKLIST;
        o.sourceHandoverId = HANDOVER;
        o.handoverIssue = handoverIssue;
        return o;
    }
}
