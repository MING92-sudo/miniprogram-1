package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 旧急修读、改、关闭端点必须与新七态流程使用同一身份和状态约束。 */
class LegacyFaultSecurityTest {

    private FaultMapper faults;
    private EmployeeScopeService scope;
    private DirectoryService svc;
    private Fault fault;

    @BeforeEach
    void setUp() {
        faults = mock(FaultMapper.class);
        scope = mock(EmployeeScopeService.class);
        svc = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                mock(ElevatorMapper.class), mock(ChecklistService.class), mock(WorkOrderService.class),
                scope, mock(EmployeeMapper.class), mock(UseUnitMapper.class),
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                mock(TenantSnapshotService.class));
        fault = new Fault();
        fault.id = "f1";
        fault.status = "PROCESSING";
        fault.elevatorCode = "DT-1";
        fault.createdBy = "creator-other-team";
        fault.dispatchWorkerId = "e1";
        when(faults.selectById("f1")).thenReturn(fault);
        when(scope.require(any())).thenAnswer(inv -> {
            Employee e = new Employee();
            e.id = inv.getArgument(0);
            return e;
        });
        when(scope.visibleCreatorIds(any())).thenReturn(Set.of("creator-my-team"));
    }

    @Test
    void assignedWorkerCanReadEvenWhenCreatorIsOutsideGroup() {
        assertNotNull(svc.getFault("f1", "e1"));
    }

    @Test
    void openPoolOrderCanBeReadByWorker() {
        fault.status = "OPEN";
        fault.dispatchWorkerId = null;
        assertNotNull(svc.getFault("f1", "e-other"));
    }

    @Test
    void unrelatedWorkerCannotReadOrUpdate() {
        BizException read = assertThrows(BizException.class, () -> svc.getFault("f1", "e-other"));
        assertEquals(1403, read.getCode());
        BizException write = assertThrows(BizException.class,
                () -> svc.updateFault("f1", Map.of("siteDesc", "伪造"), "e-other"));
        assertEquals(1403, write.getCode());
        verify(faults, never()).updateById(any(Fault.class));
    }

    @Test
    void unassignedWorkerCannotCloseAndWrongStateCannotClose() {
        BizException denied = assertThrows(BizException.class,
                () -> svc.closeFault("f1", Map.of("result", "修复", "signature", "sign"), "e-other"));
        assertEquals(1403, denied.getCode());
        BizException state = assertThrows(BizException.class,
                () -> svc.closeFault("f1", Map.of("result", "修复", "signature", "sign"), "e1"));
        assertEquals(1403, state.getCode());
        verify(faults, never()).updateById(any(Fault.class));
    }

    @Test
    void terminalAndPendingConfirmResultsCannotBeRewritten() {
        for (String status : new String[]{"PENDING_CONFIRM", "CLOSED", "CANCELLED"}) {
            fault.status = status;
            BizException ex = assertThrows(BizException.class,
                    () -> svc.updateFault("f1", Map.of("handleDesc", "篡改结果"), "e1"), status);
            assertEquals(1003, ex.getCode(), status);
        }
        verify(faults, never()).updateById(any(Fault.class));
    }

    @Test
    void legacyNullCreatorDoesNotBypassScope() {
        fault.createdBy = null;
        fault.status = "CLOSED";
        fault.dispatchWorkerId = "e-other";
        BizException ex = assertThrows(BizException.class, () -> svc.getFault("f1", "e-other2"));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void assignedWorkerCannotCloseForClient() {
        fault.status = "PENDING_CONFIRM";
        BizException ex = assertThrows(BizException.class,
                () -> svc.closeFault("f1", Map.of("result", "已修复", "signature", "file-sign"), "e1"));
        assertEquals(1403, ex.getCode());
        assertEquals("PENDING_CONFIRM", fault.status);
        verify(faults, never()).updateById(any(Fault.class));
    }
}
