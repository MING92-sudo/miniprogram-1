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
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(orders.update(any(), any())).thenReturn(1);
        employees = mock(EmployeeMapper.class);
        files = mock(FileStorageService.class);
        pdf = mock(RecordPdfService.class);
        configs = mock(SysConfigMapper.class);
        svc = new RecordLifecycleService(records, orders, employees,
                mock(ElevatorMapper.class), mock(UseUnitMapper.class), pdf, files, configs);
    }

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
    }

    private MaintainRecord record(String lifecycle) {
        MaintainRecord r = new MaintainRecord();
        r.id = "r1";
        r.lifecycleStatus = lifecycle;
        r.confirmStatus = "PENDING";
        r.shareToken = "sg-token";
        r.reportStatus = "SUCCESS";
        r.workOrderId = "o1";
        when(records.selectById("r1")).thenReturn(r);
        when(records.update(any(), any())).thenReturn(1);
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
    void platformAdminCanReviewAndRequestSignAccordingToMatrix() {
        MaintainRecord r = record("PENDING_REVIEW");
        svc.review("r1", true, "", Set.of("PLATFORM_ADMIN"));
        assertEquals("PENDING_CLIENT_SIGN", r.lifecycleStatus);
        r.workerSignatureUrl = "files/w";
        r.assistantSignatureUrl = "files/a";
        svc.signRequest("r1", Set.of("PLATFORM_ADMIN"));
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
    }

    @Test
    void reviewSignRequestAndArchiveRejectCrossGroupRecords() {
        MaintainRecord r = record("PENDING_REVIEW");
        r.workerPlatformId = "pid_other";
        EmployeeScopeService scope = mock(EmployeeScopeService.class);
        Employee me = new Employee();
        me.id = "emp_1";
        when(scope.require("emp_1")).thenReturn(me);
        when(scope.visibleReadPlatformIds(me)).thenReturn(Set.of("pid_team"));
        svc.setScopeService(scope);

        BizException review = assertThrows(BizException.class,
                () -> svc.review("r1", true, "", Set.of("MAINT_SUP"), "emp_1"));
        BizException sign = assertThrows(BizException.class,
                () -> svc.signRequest("r1", Set.of("MAINT_SUP"), "emp_1"));
        BizException archive = assertThrows(BizException.class, () -> svc.archiveView("r1", "emp_1"));

        assertEquals(1403, review.getCode());
        assertEquals(1403, sign.getCode());
        assertEquals(1403, archive.getCode());
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
    void signRequestByRecordIdAdvancesAndReturnsShareToken() {
        MaintainRecord r = record("PENDING_CLIENT_SIGN");
        r.workerSignatureUrl = "files/w";
        r.assistantSignatureUrl = "files/a";
        Map<String, Object> out = svc.signRequest("r1", Set.of("MAINT_TECH"));
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
        assertEquals("sg-token", out.get("shareToken"));
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
    void signatureRefuseClearsDatabaseColumns() {
        WorkOrder o = new WorkOrder();
        o.id = "o1";
        o.status = "PROCESSING";
        o.assistantPlatformId = "P-Assist";
        o.assistantSignatureFileId = "file-a";
        o.assistantSignatureAt = com.cqwlw.maintenance.util.TimeUtil.now();
        when(orders.selectById("o1")).thenReturn(o);
        Employee me = new Employee();
        me.id = "e1";
        me.platformId = "P-Assist";
        when(employees.selectById("e1")).thenReturn(me);

        svc.signatureRefuse("o1", "e1");

        assertNull(o.assistantSignatureFileId);
        assertNull(o.assistantSignatureAt);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WorkOrder>>
                captor = org.mockito.ArgumentCaptor.forClass(
                        (Class) com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(orders).update(any(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("assistant_signature_file_id"));
        assertTrue(captor.getValue().getSqlSet().contains("assistant_signature_at"));
        verify(orders, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void signatureRefuseRejectsConcurrentReassignment() {
        WorkOrder o = new WorkOrder();
        o.id = "o1";
        o.status = "PROCESSING";
        o.assistantPlatformId = "P-Assist";
        o.assistantSignatureFileId = "file-a";
        when(orders.selectById("o1")).thenReturn(o);
        Employee me = new Employee();
        me.id = "e1";
        me.platformId = "P-Assist";
        when(employees.selectById("e1")).thenReturn(me);
        when(orders.update(any(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> svc.signatureRefuse("o1", "e1"));

        assertEquals(1003, e.getCode());
        assertEquals("file-a", o.assistantSignatureFileId);
        verify(orders, never()).clearAssistantSignature(any());
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
        assertEquals("确认链接无效或已失效", ex.getMessage());
    }

    @Test
    void refuseByTokenUnknownRecordIs401() {
        when(records.selectById("ur_missing")).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("ur_missing", "sg-token", "照片不清晰"));
        assertEquals(401, ex.getCode());
        assertEquals("确认链接无效或已失效", ex.getMessage());
        verify(records, never()).update(any(), any());
        verify(records, never()).updateById(any(MaintainRecord.class));
    }

    @Test
    void internalPathsUnknownRecordStill404() {
        when(records.selectById("ur_missing")).thenReturn(null);
        assertEquals(1404, assertThrows(BizException.class,
                () -> svc.review("ur_missing", true, "", Set.of("MAINT_SUP"))).getCode());
        assertEquals(1404, assertThrows(BizException.class,
                () -> svc.signRequest("ur_missing", Set.of("MAINT_TECH"))).getCode());
        assertEquals(1404, assertThrows(BizException.class,
                () -> svc.archiveView("ur_missing")).getCode());
        verify(records, never()).updateById(any(MaintainRecord.class));
    }

    @Test
    void refuseByTokenRejectedWhenAlreadyConfirmed() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        r.confirmStatus = "CONFIRMED";
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("r1", "sg-token", "不认可"));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
        verify(records, never()).update(any(), any());
    }

    @Test
    void refuseByTokenRejectedForSupersededVersion() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        when(records.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("r1", "sg-token", "不认可"));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
        verify(records, never()).update(any(), any());
    }

    @Test
    void refuseByTokenConditionalUpdateZeroRows() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        when(records.update(any(), any())).thenReturn(0);
        BizException ex = assertThrows(BizException.class,
                () -> svc.refuseByToken("r1", "sg-token", "不认可"));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
        assertNull(r.refuseReason);
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
    void archivePersistsOnlyArchiveFields() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE");
        when(pdf.render(any(), any(), any())).thenAnswer(inv -> {
            MaintainRecord rec = inv.getArgument(0);
            rec.workerSignatureUrl = "https://x/files/f_sig?s=expiring"; // render 内存回填时效 URL
            return new byte[]{1};
        });
        AppFile f = new AppFile();
        f.id = "file_arch";
        when(files.storeBytes(any(), any(), any(), any())).thenReturn(f);

        svc.onClientSigned(r);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MaintainRecord>>
                captor = org.mockito.ArgumentCaptor.forClass(
                        (Class) com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(records, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("archive_file_id"));
        assertTrue(sqlSet.contains("lifecycle_status"));
        org.junit.jupiter.api.Assertions.assertFalse(sqlSet.contains("signature_url"));
        verify(records, never()).updateById(any(MaintainRecord.class));
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