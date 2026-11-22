package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClientSignSnapshotTest {

    private MaintainRecordMapper recordMapper;
    private AuditLogService auditLogService;
    private UnitRecordService service;

    /** 纯单测环境初始化 MyBatis-Plus lambda 列缓存（全量跑时由其他测试初始化，独立跑需显式初始化） */
    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), MaintainRecord.class);
    }

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        auditLogService = mock(AuditLogService.class);
        FileStorageService fileStorage = mock(FileStorageService.class);
        WorkOrderService workOrderService = mock(WorkOrderService.class);
        com.cqwlw.maintenance.mapper.ElevatorMapper elevatorMapper =
                mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class);
        service = new UnitRecordService(recordMapper, workOrderService, fileStorage, elevatorMapper);
        service.setAuditLogService(auditLogService);
    }

    private MaintainRecord signable() {
        MaintainRecord r = new MaintainRecord();
        r.id = "rec_1";
        r.useUnitId = "uu_1";
        r.confirmStatus = "PENDING";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.version = 1;
        return r;
    }

    private Map<String, Object> body() {
        return Map.of("signatureUrl", "https://x/sign.png");
    }

    @Test
    void clientSignWritesTenantAudit() {
        when(recordMapper.selectById("rec_1")).thenReturn(signable());
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(recordMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.confirmAs("rec_1", body(), Set.of("CLIENT_ADMIN"), "uu_1", "ca_1", "王芳");

        ArgumentCaptor<SysAuditLog> auditCap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertEquals("uu_1", auditCap.getValue().useUnitId);
        assertEquals("CLIENT_RECORD_SIGN", auditCap.getValue().action);
        assertEquals("ca_1", auditCap.getValue().actorId);
        assertEquals("CUSTOMER", auditCap.getValue().actorRole);
        assertTrue(auditCap.getValue().reason.contains("王芳"));
    }

    @Test
    void anonymousTokenSignWritesAnonymousAudit() {
        MaintainRecord r = signable();
        r.shareToken = "share_1";
        when(recordMapper.selectById("rec_1")).thenReturn(r);
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(recordMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.confirmByToken("rec_1", "share_1", body());

        ArgumentCaptor<SysAuditLog> auditCap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertEquals("ANONYMOUS", auditCap.getValue().actorRole);
        assertTrue(auditCap.getValue().actorId.isEmpty());
        assertEquals("uu_1", auditCap.getValue().useUnitId);
    }

    @Test
    void auditFailureDoesNotBreakSign() {
        when(recordMapper.selectById("rec_1")).thenReturn(signable());
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(recordMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        doThrow(new RuntimeException("db down")).when(auditLogService).append(any(SysAuditLog.class));

        assertDoesNotThrow(() ->
                service.confirmAs("rec_1", body(), Set.of("CLIENT_ADMIN"), "uu_1", "ca_1", "王芳"));
        verify(recordMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void concurrentSignRejectedWhenUpdateAffectsZero() {
        when(recordMapper.selectById("rec_1")).thenReturn(signable());
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(recordMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0); // 已被首签

        BizException e = assertThrows(BizException.class,
                () -> service.confirmAs("rec_1", body(), Set.of("CLIENT_ADMIN"), "uu_1", "ca_1", "王芳"));
        assertEquals(422, e.getCode());
        verify(auditLogService, never()).append(any(SysAuditLog.class)); // 未签成功不写签字审计
    }

    @Test
    void signRequiresSignature() {
        when(recordMapper.selectById("rec_1")).thenReturn(signable());
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        BizException e = assertThrows(BizException.class,
                () -> service.confirmAs("rec_1", Map.of(), Set.of("CLIENT_ADMIN"), "uu_1", "ca_1", "王芳"));
        assertEquals(422, e.getCode());
    }

    @Test
    void nonCustomerRoleCannotSign() {
        when(recordMapper.selectById("rec_1")).thenReturn(signable());
        BizException e = assertThrows(BizException.class,
                () -> service.confirmAs("rec_1", body(), Set.of("WORKER"), "uu_1", null, null));
        assertEquals(1403, e.getCode());
    }
}
