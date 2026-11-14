package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.BizExportApproval;
import com.cqwlw.maintenance.mapper.BizExportApprovalMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M5 数据域：配置范围校验/敏感导出审批（用途≥10字、仅待审批可批）。 */
class DataDomainServiceTest {

    private final SysConfigMapper configMapper = mock(SysConfigMapper.class);
    private final BizExportApprovalMapper approvalMapper = mock(BizExportApprovalMapper.class);
    private final SysAuditLogMapper auditMapper = mock(SysAuditLogMapper.class);
    private final ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
    private final WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
    private final MaintainRecordMapper recordMapper = mock(MaintainRecordMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final DataDomainService service = new DataDomainService(configMapper, approvalMapper, auditMapper,
            elevatorMapper, workOrderMapper, recordMapper, audit);

    @Test
    void configRangeValidation() {
        assertThrows(BizException.class, () -> service.setConfig("sla_minutes", "0", "admin"));
        assertThrows(BizException.class, () -> service.setConfig("sla_minutes", "241", "admin"));
        assertThrows(BizException.class, () -> service.setConfig("spotcheck_ratio", "101", "admin"));
    }

    @Test
    void exportPurposeMinLength() {
        assertThrows(BizException.class, () -> service.requestExport("短", null, "u"));
        BizExportApproval a = service.requestExport("用于年度监管检查导出", "all", "u");
        assertEquals("PENDING", a.status);
    }

    @Test
    void approveOnlyPending() {
        BizExportApproval a = new BizExportApproval();
        a.id = "e1";
        a.status = "APPROVED";
        when(approvalMapper.selectById("e1")).thenReturn(a);
        assertThrows(BizException.class, () -> service.approveExport("e1", "admin"));
    }
}