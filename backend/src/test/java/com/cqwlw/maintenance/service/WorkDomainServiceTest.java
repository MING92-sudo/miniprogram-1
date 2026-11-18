package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M4 作业域：记录修订 v2+哈希（仅已归档）、代报修校验。 */
class WorkDomainServiceTest {

    private final MaintainRecordMapper recordMapper = mock(MaintainRecordMapper.class);
    private final FaultMapper faultMapper = mock(FaultMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WorkDomainService service = new WorkDomainService(recordMapper, faultMapper, audit);

    private MaintainRecord archived() {
        MaintainRecord r = new MaintainRecord();
        r.id = "r1";
        r.confirmStatus = "CONFIRMED";
        r.version = 1;
        r.itemsJson = "[]";
        return r;
    }

    @Test
    void amendOnlyArchived() {
        MaintainRecord r = archived();
        r.confirmStatus = "PENDING";
        when(recordMapper.selectById("r1")).thenReturn(r);
        BizException e = assertThrows(BizException.class, () -> service.amend("r1", "reason", "mgr"));
        assertEquals(422, e.getCode());
    }

    @Test
    void amendCreatesV2WithHashAndPrev() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        MaintainRecord v2 = service.amend("r1", "更正项", "mgr");
        assertEquals(2, v2.version);
        assertEquals("r1", v2.prevVersionId);
        assertNotNull(v2.previewContextJson);
        assertEquals(64, v2.previewContextJson.length());
        verify(recordMapper).insert(any(MaintainRecord.class));
    }

    @Test
    void amendRequiresReason() {
        when(recordMapper.selectById("r1")).thenReturn(archived());
        assertThrows(BizException.class, () -> service.amend("r1", "", "mgr"));
    }

    @Test
    void amendCopiesTenantSnapshotFromV1() {
        MaintainRecord v1 = archived();
        v1.useUnitId = "uu1";
        v1.useUnitName = "启元物业";
        v1.elevatorId = "e1";
        when(recordMapper.selectById("r1")).thenReturn(v1);
        MaintainRecord v2 = service.amend("r1", "内容有误", "emp1");
        assertEquals("uu1", v2.useUnitId);
        assertEquals("启元物业", v2.useUnitName);
        assertEquals("e1", v2.elevatorId);
    }

    @Test
    void amendCopiesNullSnapshotWithoutElevatorLookup() {
        MaintainRecord v1 = archived();
        v1.useUnitId = null;
        v1.useUnitName = null;
        v1.elevatorId = null;
        when(recordMapper.selectById("r1")).thenReturn(v1);
        MaintainRecord v2 = service.amend("r1", "内容有误", "emp1");
        assertNull(v2.useUnitId);
        assertNull(v2.useUnitName);
        assertNull(v2.elevatorId);
    }

    @Test
    void proxyValidatesRecordingNo() {
        Fault f = new Fault();
        f.id = "f1";
        when(faultMapper.selectById("f1")).thenReturn(f);
        assertThrows(BizException.class, () -> service.proxyRepair("f1", Map.of("proxySource", "PHONE", "recordingNo", "BAD"), "u"));
        Fault ok = service.proxyRepair("f1", Map.of("proxySource", "PHONE", "recordingNo", "REC-1234"), "u");
        assertEquals("PHONE", ok.proxySource);
        assertEquals("u", ok.proxyBy);
    }
}