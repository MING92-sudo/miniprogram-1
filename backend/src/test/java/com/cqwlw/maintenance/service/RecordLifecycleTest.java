package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记录生命周期（M1，蓝图 §6.2 / 裁决 R1/D2）：复核可配、发起签署门控、配合退回、
 * 拒签原因必填、自动归档 SHA-256 且不重报 2.6、越权无副作用。
 */
class RecordLifecycleTest {

    private MaintainRecordMapper records;
    private WorkOrderMapper orders;
    private EmployeeMapper employees;
    private FileStorageService files;
    private RecordPdfService pdf;
    private SysConfigMapper configs;
    private RecordLifecycleService svc;

    @BeforeEach
    void setUp() {
        records = mock(MaintainRecordMapper.class);
        orders = mock(WorkOrderMapper.class);
        employees = mock(EmployeeMapper.class);
        files = mock(FileStorageService.class);
        pdf = mock(RecordPdfService.class);
        configs = mock(SysConfigMapper.class);
        svc = new RecordLifecycleService(records, orders, employees,
                mock(ElevatorMapper.class), mock(UseUnitMapper.class), pdf, files, configs);
    }

    private MaintainRecord record(String lifecycle) {
        MaintainRecord r = new MaintainRecord();
        r.id = "r1";
        r.lifecycleStatus = lifecycle;
        r.shareToken = "sg-token";
        r.reportStatus = "SUCCESS";
        r.workOrderId = "o1";
        when(records.selectById("r1")).thenReturn(r);
        return r;
    }

    @Test
    void initialStatusDefaultsToPendingClientSign() {
        when(configs.selectOne(any())).thenReturn(null);
        assertEquals("PENDING_CLIENT_SIGN", svc.initialStatus());
    }

    @Test
    void initialStatusReviewEnabled() {
        SysConfig c = new SysConfig();
        c.cfgKey = "record_review_enabled";
        c.cfgValue = "1";
        when(configs.selectOne(any())).thenReturn(c);
        assertEquals("PENDING_REVIEW", svc.initialStatus());
    }

    @Test
    void reviewPassTransitionsToClientSign() {
        MaintainRecord r = record("PENDING_REVIEW");
        svc.review("r1", true, "", Set.of("MAINT_SUP"));
        assertEquals("PENDING_CLIENT_SIGN", r.lifecycleStatus);
    }

    @Test
    void reviewReturnRequiresReason() {
        record("PENDING_REVIEW");
        BizException ex = assertThrows(BizException.class,
                () -> svc.review("r1", false, "  ", Set.of("MAINT_SUP")));
        assertEquals(422, ex.getCode());
    }

    @Test
    void reviewReturnReopensWorkOrder() {
        MaintainRecord r = record("PENDING_REVIEW");
        WorkOrder o = new WorkOrder();
        o.id = "o1";
        o.status = "DONE";
        when(orders.selectById("o1")).thenReturn(o);
        svc.review("r1", false, "复核不通过", Set.of("MAINT_MGR"));
        assertNull(r.lifecycleStatus);
        assertEquals("PROCESSING", o.status);
    }

    @Test
    void reviewForbiddenForTechnician() {
        record("PENDING_REVIEW");
        BizException ex = assertThrows(BizException.class,
                () -> svc.review("r1", true, "", Set.of("MAINT_TECH")));
        assertEquals(1403, ex.getCode());
    }

    @Test
    void signRequestGatesDualSignature() {
        MaintainRecord r = record("PENDING_CLIENT_SIGN");
        r.workerSignatureUrl = "files/w";
        r.assistantSignatureUrl = "";
        BizException ex = assertThrows(BizException.class,
                () -> svc.signRequest("r1", Set.of("MAINT_TECH")));
        assertEquals(422, ex.getCode());
        r.assistantSignatureUrl = "files/a";
        svc.signRequest("r1", Set.of("MAINT_TECH"));
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
    }

    @Test
    void signRequestSoloExemptAllowed() {
        MaintainRecord r = record("PENDING_CLIENT_SIGN");
        r.soloExempt = 1;
        svc.signRequest("r1", Set.of("MAINT_SUP"));
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
    }

    @Test
    void signatureRefuseOnlyAssistant() {
        WorkOrder o = new WorkOrder();
        o.id = "o1";
        o.status = "PROCESSING";
        o.assistantPlatformId = "P-Assist";
        o.assistantSignatureFileId = "file-a";
        when(orders.selectById("o1")).thenReturn(o);
        Employee me = new Employee();
        me.id = "e1";
        me.platformId = "P-Other";
        when(employees.selectById("e1")).thenReturn(me);
        BizException ex = assertThrows(BizException.class, () -> svc.signatureRefuse("o1", "e1"));
        assertEquals(1403, ex.getCode());
        me.platformId = "P-Assist";
        svc.signatureRefuse("o1", "e1");
        assertNull(o.assistantSignatureFileId);
        assertNull(o.assistantSignatureAt);
        assertEquals("PROCESSING", o.status); // 无上报副作用
    }

    @Test
    void refuseByTokenRequiresReason() {
        record("PENDING_CLIENT_SIGNATURE");
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("r1", "sg-token", ""));
        assertEquals(422, ex.getCode());
    }

    @Test
    void refuseByTokenTransitions() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        svc.refuseByToken("r1", "sg-token", "照片不清晰");
        assertEquals("REFUSED_RETURNED", r.lifecycleStatus);
        assertEquals("照片不清晰", r.refuseReason);
    }

    @Test
    void refuseByTokenWrongToken401() {
        record("PENDING_CLIENT_SIGNATURE");
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("r1", "bad", "原因"));
        assertEquals(401, ex.getCode());
    }

    @Test
    void clientSignArchivesWithoutReReport() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        when(pdf.render(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});
        AppFile f = new AppFile();
        f.id = "file_arch";
        when(files.storeBytes(any(), any(), any(), any())).thenReturn(f);
        svc.onClientSigned(r);
        assertEquals("ARCHIVED", r.lifecycleStatus);
        assertEquals("file_arch", r.archiveFileId);
        assertNotNull(r.archiveHash);
        assertEquals(64, r.archiveHash.length());
        assertEquals("SUCCESS", r.reportStatus); // D2：2.6 不重报
    }

    @Test
    void archiveViewRejectsNotArchived() {
        record("PENDING_CLIENT_SIGN");
        BizException ex = assertThrows(BizException.class, () -> svc.archiveView("r1"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void archiveViewLegacySignedArchivesOnDemand() {
        MaintainRecord r = record("SIGNED");
        when(pdf.render(any(), any(), any())).thenReturn(new byte[]{9});
        AppFile f = new AppFile();
        f.id = "file_late";
        when(files.storeBytes(any(), any(), any(), any())).thenReturn(f);
        when(files.urlOf("file_late")).thenReturn("https://x/files/file_late?s=fresh");
        Map<String, Object> out = svc.archiveView("r1");
        assertEquals("ARCHIVED", r.lifecycleStatus);
        assertEquals("https://x/files/file_late?s=fresh", out.get("url"));
        assertTrue(String.valueOf(out.get("hash")).length() == 64);
    }
}