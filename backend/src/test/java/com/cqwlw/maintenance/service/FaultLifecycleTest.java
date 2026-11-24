package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultLifecycleTest {

    private FaultMapper faults;
    private ElevatorMapper elevators;
    private EmployeeMapper employees;
    private DirectoryService svc;

    @BeforeEach
    void setUp() {
        faults = mock(FaultMapper.class);
        elevators = mock(ElevatorMapper.class);
        employees = mock(EmployeeMapper.class);
        svc = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                elevators, mock(ChecklistService.class), mock(WorkOrderService.class),
                mock(EmployeeScopeService.class), employees, mock(UseUnitMapper.class),
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                mock(TenantSnapshotService.class));
        when(faults.closePending(any())).thenReturn(1);
        when(faults.refusePending(any(), any())).thenReturn(1);
    }

    private Fault fault(String status) {
        Fault f = new Fault();
        f.id = "f1";
        f.status = status;
        f.elevatorCode = "DT-1";
        f.useUnitId = "u1";
        f.dispatchWorkerId = "e1";
        when(faults.selectById("f1")).thenReturn(f);
        return f;
    }

    private void elevator(String regionId, String unitId) {
        Elevator el = new Elevator();
        el.elevatorCode = "DT-1";
        el.regionId = regionId;
        el.useUnitId = unitId;
        when(elevators.selectOne(any())).thenReturn(el);
    }

    @Test
    void concurrentGrabAllowsOnlyOneWinner() {
        Fault first = fault("OPEN");
        Fault second = fault("OPEN");
        when(faults.selectById("f1")).thenReturn(first, second);
        Employee me = new Employee();
        me.id = "e1";
        me.regionId = "r1";
        when(employees.selectById("e1")).thenReturn(me);
        elevator("r1", "u1");
        when(faults.claimOpen(any(), any(), any())).thenReturn(1, 0);

        svc.grabFault("f1", "e1");
        BizException loser = assertThrows(BizException.class, () -> svc.grabFault("f1", "e1"));

        assertEquals(422, loser.getCode());
    }

    @Test
    void refusedFaultCanBeCorrectedAndResubmittedByAssignedWorker() {
        Fault f = fault("REFUSED");
        svc.updateFault("f1", Map.of("siteDesc", "门锁故障", "handleDesc", "更换触点"), "e1");
        svc.submitFault("f1", "e1", Map.of("faultReason", "门锁故障", "handleDesc", "更换触点"));
        assertEquals("PENDING_CONFIRM", f.status);
    }

    @Test
    void grabWithinRegionAssigns() {
        Fault f = fault("OPEN");
        Employee me = new Employee();
        me.id = "e1";
        me.regionId = "r1";
        when(employees.selectById("e1")).thenReturn(me);
        elevator("r1", "u1");
        when(faults.claimOpen(any(), any(), any())).thenReturn(1);
        svc.grabFault("f1", "e1");
        assertEquals("ASSIGNED", f.status);
        assertEquals("e1", f.dispatchWorkerId);
    }

    @Test
    void grabOutOfRegionForbidden() {
        fault("OPEN");
        Employee me = new Employee();
        me.id = "e1";
        me.regionId = "r2";
        when(employees.selectById("e1")).thenReturn(me);
        elevator("r1", "u1");
        BizException ex = assertThrows(BizException.class, () -> svc.grabFault("f1", "e1"));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void arriveRequiresGps() {
        fault("ASSIGNED");
        BizException ex = assertThrows(BizException.class,
                () -> svc.arriveFault("f1", "e1", null, 106.5));
        assertEquals(422, ex.getCode());
    }

    @Test
    void arriveTransitionsToProcessing() {
        Fault f = fault("ASSIGNED");
        svc.arriveFault("f1", "e1", 29.5, 106.5);
        assertEquals("PROCESSING", f.status);
        assertNotNull(f.arrivedAt);
    }

    @Test
    void arriveOnlyAssignedWorker() {
        fault("ASSIGNED");
        BizException ex = assertThrows(BizException.class,
                () -> svc.arriveFault("f1", "e-other", 29.5, 106.5));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void submitRequiresReasonAndHandle() {
        fault("PROCESSING");
        BizException ex = assertThrows(BizException.class,
                () -> svc.submitFault("f1", "e1", Map.of("faultReason", "门锁故障")));
        assertEquals(422, ex.getCode());
    }

    @Test
    void submitTransitionsToPendingConfirm() {
        Fault f = fault("PROCESSING");
        svc.submitFault("f1", "e1", Map.of("faultReason", "门锁故障", "handleDesc", "更换触点"));
        assertEquals("PENDING_CONFIRM", f.status);
        assertNotNull(f.finishedAt);
    }

    @Test
    void submitAcceptsMiniappPayloadAndPersistsProcessFields() {
        Fault f = fault("PROCESSING");
        svc.submitFault("f1", "e1", Map.of(
                "siteDesc", "门锁故障", "todoDesc", "复查门触点", "handleDesc", "更换触点"));

        assertEquals("PENDING_CONFIRM", f.status);
        assertEquals("门锁故障", f.faultReason);
        assertEquals("门锁故障", f.siteDesc);
        assertEquals("复查门触点", f.todoDesc);
        assertNotNull(f.finishedAt);
    }

    @Test
    void confirmUsesAtomicPendingState() {
        Fault first = fault("PENDING_CONFIRM");
        Fault second = fault("PENDING_CONFIRM");
        when(faults.selectById("f1")).thenReturn(first, second);
        when(faults.closePending("f1")).thenReturn(1, 0);
        when(faults.refusePending(any(), any())).thenReturn(0);

        svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), "u1", true, "");
        BizException loser = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), "u1", false, "拒绝"));

        assertEquals(422, loser.getCode());
    }

    @Test
    void confirmRequiresClientSigner() {
        fault("PENDING_CONFIRM");
        BizException ex = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_ADMIN"), "u1", true, ""));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void confirmClosesAndRefuseNeedsReason() {
        Fault f = fault("PENDING_CONFIRM");
        svc.confirmFault("f1", Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), "u1", true, "");
        assertEquals("CLOSED", f.status);
        assertNotNull(f.confirmedAt);
        Fault f2 = fault("PENDING_CONFIRM");
        BizException ex = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), "u1", false, " "));
        assertEquals(422, ex.getCode());
    }

    @Test
    void confirmRequiresUnitAnchorAndMatchingSnapshot() {
        fault("PENDING_CONFIRM");
        BizException missing = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), null, true, ""));
        assertEquals(1403, missing.getCode());
        BizException other = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), "u-other", true, ""));
        assertEquals(1403, other.getCode());
    }

    @Test
    void confirmCrossUnitForbidden() {
        fault("PENDING_CONFIRM");
        elevator("r1", "u-other");
        BizException ex = assertThrows(BizException.class,
                () -> svc.confirmFault("f1", Set.of("CLIENT_SIGNER"), "u-other", true, ""));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void cancelRolesAndReason() {
        fault("OPEN");
        BizException noReason = assertThrows(BizException.class,
                () -> svc.cancelFault("f1", Set.of("CLIENT_ADMIN"), "", "e1", "u1"));
        assertEquals(422, noReason.getCode());
        BizException wrongRole = assertThrows(BizException.class,
                () -> svc.cancelFault("f1", Set.of("MAINT_SUP"), "误报", "e1", "u1"));
        assertEquals(1403, wrongRole.getCode());
        Fault f = fault("OPEN");
        svc.cancelFault("f1", Set.of("CLIENT_ADMIN"), "误报", "e1", "u1");
        assertEquals("CANCELLED", f.status);
    }

    @Test
    void evaluateOnlyClosedOnce() {
        fault("PROCESSING");
        BizException notClosed = assertThrows(BizException.class,
                () -> svc.evaluate("f1", Set.of("CLIENT_ADMIN"), "u1", Map.of("score", 5)));
        assertEquals(422, notClosed.getCode());
        Fault f = fault("CLOSED");
        svc.evaluate("f1", Set.of("CLIENT_ADMIN"), "u1", Map.of("score", 5, "text", "响应快"));
        assertEquals(5, f.evaluateScore);
        assertNotNull(f.evaluatedAt);
        BizException dup = assertThrows(BizException.class,
                () -> svc.evaluate("f1", Set.of("CLIENT_ADMIN"), "u1", Map.of("score", 4)));
        assertEquals(422, dup.getCode());
    }

    @Test
    void evaluateScoreRange() {
        fault("CLOSED");
        BizException ex = assertThrows(BizException.class,
                () -> svc.evaluate("f1", Set.of("CLIENT_ADMIN"), "u1", Map.of("score", 6)));
        assertEquals(422, ex.getCode());
    }

    /** 急修投影派生字段：rescue = 困人（被困人数>0 或类型含困人）；responseMinutes = 报修→到场分钟数 */
    @Test
    void faultViewDerivesRescueAndResponseMinutes() {
        Fault f = new Fault();
        f.id = "fa_rescue";
        f.trappedCount = 2;
        f.createdAt = java.time.LocalDateTime.of(2026, 11, 24, 10, 0);
        f.arrivedAt = java.time.LocalDateTime.of(2026, 11, 24, 10, 25);
        Map<String, Object> v = svc.faultView(f);
        assertEquals(true, v.get("rescue"));
        assertEquals(25L, v.get("responseMinutes"));

        Fault plain = new Fault();
        plain.id = "fa_plain";
        plain.faultType = "异响抖动";
        Map<String, Object> v2 = svc.faultView(plain);
        assertEquals(false, v2.get("rescue"));
        assertNull(v2.get("responseMinutes"), "未到场不得给出响应时长");
    }

    /** 急修投影补齐管理端展示字段：SLA/期限/代报修来源/录音编号/升级/取消/拒签原因/被指派人 */
    @Test
    void faultViewExposesSlaProxyAndLifecycleFields() {
        Fault f = fault("OPEN");
        f.dispatchWorkerId = "emp_9";
        f.slaMinutes = 120;
        f.deadline = java.time.LocalDateTime.of(2026, 11, 24, 18, 0);
        f.proxySource = "PHONE";
        f.recordingNo = "REC-1";
        f.escalatedAt = java.time.LocalDateTime.of(2026, 11, 24, 17, 0);
        f.cancelReason = "重复报修";
        f.refuseReason = "配件缺失";

        Map<String, Object> v = svc.faultView(f);

        assertEquals("emp_9", v.get("dispatchWorkerId"));
        assertEquals(120, v.get("slaMinutes"));
        assertEquals("2026-11-24 18:00:00", v.get("deadline"));
        assertEquals("PHONE", v.get("proxySource"));
        assertEquals("REC-1", v.get("recordingNo"));
        assertEquals("2026-11-24 17:00:00", v.get("escalatedAt"));
        assertEquals("重复报修", v.get("cancelReason"));
        assertEquals("配件缺失", v.get("refuseReason"));
    }
}
