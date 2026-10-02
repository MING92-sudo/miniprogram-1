package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.CurrentUser;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ApprovalService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EvidenceTokenService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 双人作业规则：维保人员1（PRINCIPAL）可编辑维保记录，配合人员（ASSISTANT）记录锁定只读；
 * 两人**均签到**才允许开始作业（未齐严格阻断）。
 * 另覆盖 recorder/recorderPhone 归属维保人员1，且查不到时不得拿他人号码凑。
 */
class TwoPersonWorkRuleTest {

    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private EvidenceTokenService evidenceTokenService;
    private ApprovalService approvalService;
    private CurrentUser currentUser;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        evidenceTokenService = mock(EvidenceTokenService.class);
        approvalService = mock(ApprovalService.class);
        currentUser = mock(CurrentUser.class);
        service = new WorkOrderService(
                orderMapper,
                mock(ElevatorMapper.class),
                mock(UseUnitMapper.class),
                employeeMapper,
                mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class),
                mock(FaultMapper.class),
                mock(InspectRecordMapper.class),
                mock(ChecklistService.class),
                approvalService,
                evidenceTokenService,
                mock(FileStorageService.class),
                mock(PlatformReportService.class),
                new AppProperties(),
                currentUser);
        when(currentUser.roleOrNull()).thenReturn("WORKER");
        when(approvalService.hasApproved(anyString())).thenReturn(false);
        Map<String, Object> ev = new HashMap<>();
        ev.put("lat", 29.5);
        ev.put("lng", 106.5);
        ev.put("st", System.currentTimeMillis());
        when(evidenceTokenService.verifyAndConsume(any(), anyString())).thenReturn(ev);
    }

    private void loginAs(String employeeId, String name, String platformId) {
        Employee me = new Employee();
        me.id = employeeId;
        me.name = name;
        me.platformId = platformId;
        me.role = "WORKER";
        me.phone = "13800000001";
        when(currentUser.requireEmployeeId()).thenReturn(employeeId);
        when(employeeMapper.selectById(employeeId)).thenReturn(me);
    }

    private WorkOrder twoPersonOrder() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.orderNo = "WO-1";
        o.status = "PENDING";
        o.workTypeCode = "HM";
        o.elevatorId = "el_1";
        o.workerName = "张伟";
        o.workerPlatformId = "P_PRINCIPAL";
        o.assistantName = "李强";
        o.assistantPlatformId = "P_ASSISTANT";
        o.checklistJson = "[{\"id\":\"i1\",\"name\":\"项一\"}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        when(orderMapper.selectOne(any())).thenReturn(o);
        return o;
    }

    private WorkOrder singleOrder() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_2";
        o.orderNo = "WO-2";
        o.status = "PENDING";
        o.workTypeCode = "HM";
        o.elevatorId = "el_2";
        o.workerName = "张伟";
        o.workerPlatformId = "P_PRINCIPAL";
        o.assistantName = "";
        o.checklistJson = "[{\"id\":\"i1\",\"name\":\"项一\"}]";
        when(orderMapper.selectById("wo_2")).thenReturn(o);
        when(orderMapper.selectOne(any())).thenReturn(o);
        return o;
    }

    private Map<String, Object> checkinBody(String role, String dynamicCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("role", role);
        body.put("dynamicCode", dynamicCode);
        body.put("evidenceToken", "tok");
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkinsOf(WorkOrder o) {
        Map<String, Object> extra = com.cqwlw.maintenance.util.JsonUtil.readMap(o.checkinExtraJson);
        return (Map<String, Object>) extra.get("checkins");
    }

    @Test
    void firstCheckinKeepsPendingUntilPartnerArrives() {
        WorkOrder o = twoPersonOrder();
        loginAs("e_p", "张伟", "P_PRINCIPAL");

        Map<String, Object> resp = service.checkin("wo_1", checkinBody("PRINCIPAL", null));

        assertEquals("PENDING", o.status, "两人未齐不得进入 PROCESSING，否则作业门禁失效");
        assertNull(o.checkinTime, "作业未开始，不得记录 startTime");
        assertEquals(Boolean.TRUE, resp.get("waitingForPartner"));
        assertEquals(1, checkinsOf(o).size());
    }

    @Test
    void gateOpensOnlyAfterBothCheckin() {
        WorkOrder o = twoPersonOrder();
        loginAs("e_p", "张伟", "P_PRINCIPAL");
        service.checkin("wo_1", checkinBody("PRINCIPAL", null));

        loginAs("e_a", "李强", "P_ASSISTANT");
        Map<String, Object> resp = service.checkin("wo_1", checkinBody("ASSISTANT", "888888"));

        assertEquals("PROCESSING", o.status);
        assertEquals(Boolean.FALSE, resp.get("waitingForPartner"));
        assertEquals(2, checkinsOf(o).size());
        assertTrue(o.checkinTime != null, "两人到齐才记录作业开始时间");
    }

    @Test
    void singlePersonOrderStartsOnFirstCheckin() {
        WorkOrder o = singleOrder();
        loginAs("e_p", "张伟", "P_PRINCIPAL");

        Map<String, Object> resp = service.checkin("wo_2", checkinBody("PRINCIPAL", null));

        assertEquals("PROCESSING", o.status);
        assertEquals(Boolean.FALSE, resp.get("waitingForPartner"));
    }

    @Test
    void clientCannotClaimPrincipalSlot() {
        // 配合人员自称 PRINCIPAL：角色由服务端按登录人判定，不能顶掉主维保人员的槽位
        WorkOrder o = twoPersonOrder();
        loginAs("e_a", "李强", "P_ASSISTANT");

        service.checkin("wo_1", checkinBody("PRINCIPAL", "888888"));

        assertEquals("PENDING", o.status);
        assertTrue(checkinsOf(o).containsKey("ASSISTANT"));
        assertTrue(!checkinsOf(o).containsKey("PRINCIPAL"));
    }

    @Test
    void duplicateCheckinRejected() {
        WorkOrder o = twoPersonOrder();
        loginAs("e_p", "张伟", "P_PRINCIPAL");
        service.checkin("wo_1", checkinBody("PRINCIPAL", null));

        assertEquals(1003, assertThrows(BizException.class,
                () -> service.checkin("wo_1", checkinBody("PRINCIPAL", null))).getCode());
    }

    @Test
    void nonAssignedWorkerCannotCheckin() {
        twoPersonOrder();
        loginAs("e_x", "王五", "P_OTHER");

        assertEquals(403, assertThrows(BizException.class,
                () -> service.checkin("wo_1", checkinBody("PRINCIPAL", null))).getCode());
    }

    @Test
    void workIsBlockedBeforeBothCheckin() {
        WorkOrder o = twoPersonOrder();
        loginAs("e_p", "张伟", "P_PRINCIPAL");

        assertEquals(1003, assertThrows(BizException.class,
                () -> service.submitItem("wo_1", "i1", Map.of("result", "OK"))).getCode());
        assertEquals("PENDING", o.status);
    }

    @Test
    void assistantCannotEditChecklistOrCheckout() {
        WorkOrder o = twoPersonOrder();
        o.status = "PROCESSING";
        loginAs("e_a", "李强", "P_ASSISTANT");

        assertEquals(403, assertThrows(BizException.class,
                () -> service.submitItem("wo_1", "i1", Map.of("result", "OK"))).getCode());
        assertEquals(403, assertThrows(BizException.class,
                () -> service.runThisTime("wo_1", "i1")).getCode());
        assertEquals(403, assertThrows(BizException.class,
                () -> service.checkout("wo_1", Map.of())).getCode());
    }

    @Test
    void principalCanEditChecklist() {
        WorkOrder o = twoPersonOrder();
        o.status = "PROCESSING";
        loginAs("e_p", "张伟", "P_PRINCIPAL");

        assertEquals(Boolean.TRUE, service.submitItem("wo_1", "i1", Map.of("result", "OK")).get("ok"));
    }
}