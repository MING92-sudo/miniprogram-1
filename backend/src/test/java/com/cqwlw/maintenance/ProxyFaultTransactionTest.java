package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AuditLogService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DirectoryService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.MigrationIssueService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.TenantSnapshotService;
import com.cqwlw.maintenance.service.WorkDomainService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组合代报修：参数全部校验后，建单 + 来源 + 审计必须同事务落库。 */
class ProxyFaultTransactionTest {

    private static final String ACTOR = "emp_1";

    private FaultMapper faultMapper;
    private AuditLogService audit;
    private TxTestSupport.CapturingTx tx;
    private WorkDomainService service;
    private Fault inserted;

    @BeforeEach
    void setUp() {
        faultMapper = mock(FaultMapper.class);
        audit = mock(AuditLogService.class);
        tx = TxTestSupport.capturingTx();
        UseUnitMapper useUnits = mock(UseUnitMapper.class);
        TenantSnapshotService tenantSnapshot = new TenantSnapshotService(
                useUnits, mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        DirectoryService directoryService = new DirectoryService(
                mock(RescueMapper.class), faultMapper, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                mock(ElevatorMapper.class), mock(ChecklistService.class), mock(WorkOrderService.class),
                mock(EmployeeScopeService.class), mock(EmployeeMapper.class), useUnits,
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                tenantSnapshot);
        service = new WorkDomainService(mock(MaintainRecordMapper.class), faultMapper, audit,
                mock(RecordLifecycleService.class), directoryService, tx.template);
        doAnswer(inv -> {
            inserted = inv.getArgument(0);
            return 1;
        }).when(faultMapper).insert(any(Fault.class));
        when(faultMapper.selectById(anyString())).thenAnswer(inv -> inserted);
    }

    @Test
    void invalidProxyMetadataLeavesNoFaultAndNoAudit() {
        BizException e = assertThrows(BizException.class, () -> service.createProxyFault(Map.of(
                "elevatorCode", "EM-1", "desc", "厅门异响",
                "proxySource", "BAD"), ACTOR));

        assertEquals(422, e.getCode());
        verify(faultMapper, never()).insert(any(Fault.class));
        verify(faultMapper, never()).updateById(any(Fault.class));
        verify(audit, never()).append(any(SysAuditLog.class));
        assertFalse(tx.committed);
    }

    @Test
    void mapperExceptionAfterFaultInsertRollsBackFaultAndAudit() {
        when(faultMapper.updateById(any(Fault.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.createProxyFault(validBody(), ACTOR));

        verify(faultMapper).insert(any(Fault.class));
        verify(audit, never()).append(any(SysAuditLog.class));
        assertTrue(tx.rolledBack, "建单后落库失败必须整体回滚，不得留下无来源的急修单");
    }

    @Test
    void validInputCreatesFaultWithProxyFieldsAndOneAudit() {
        Map<String, Object> view = service.createProxyFault(validBody(), ACTOR);

        ArgumentCaptor<Fault> insert = ArgumentCaptor.forClass(Fault.class);
        verify(faultMapper).insert(insert.capture());
        ArgumentCaptor<Fault> update = ArgumentCaptor.forClass(Fault.class);
        verify(faultMapper).updateById(update.capture());
        Fault saved = update.getValue();
        assertEquals(insert.getValue().id, view.get("id"));
        assertEquals("PHONE", saved.proxySource);
        assertEquals(ACTOR, saved.proxyBy);

        ArgumentCaptor<SysAuditLog> auditRow = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(audit).append(auditRow.capture());
        assertEquals("order:create_proxy", auditRow.getValue().action);
        assertEquals(insert.getValue().id, auditRow.getValue().target);
        assertEquals(ACTOR, auditRow.getValue().actorId);
        assertTrue(tx.committed);
    }

    private static Map<String, Object> validBody() {
        return Map.of("elevatorCode", "EM-1", "desc", "厅门异响",
                "proxySource", "PHONE");
    }
}
