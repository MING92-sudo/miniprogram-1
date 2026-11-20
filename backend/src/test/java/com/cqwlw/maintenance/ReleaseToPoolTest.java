package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AuditLogService;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端「退回池」（2026-11-20 区域抢单池设计 §4.4 / D10/M12）：
 * ①DONE 工单 422（维保记录已生成并上报监管平台，不得清回池）；
 * ②未认领 PENDING 工单幂等 ok，不改状态、不写审计、不动库；
 * ③已认领工单先写审计（操作人/原认领人/原配合人/签到留痕快照 JSON，M12 审计先行），
 *   再在同一事务内清空认领/签到留痕回 PENDING——审计失败即中止回滚，不得先清库后丢审计；
 * ④清库后 worker 系、assistant 系字段与签到留痕全空、status=PENDING 重新入池，regionId 快照不变（M10）。
 */
class ReleaseToPoolTest {

    private static final String ORDER = "wo_release";
    private static final String ACTOR = "emp_admin";

    private WorkOrderMapper orderMapper;
    private AuditLogService auditLogService;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，同 ElevatorInactiveTest 口径）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);

        orderMapper = mock(WorkOrderMapper.class);
        auditLogService = mock(AuditLogService.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(orderMapper, mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                mock(EmployeeMapper.class), mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class), mock(PlatformReportService.class),
                mock(EmployeeScopeService.class), mock(CheckinThresholdService.class),
                mock(FileStorageService.class), TxTestSupport.noopTx(), props);
        service.setAuditLogService(auditLogService);
    }

    /** 已认领工单：含原认领人/配合人/签到留痕，PROCESSING */
    private WorkOrder claimed(String status) {
        WorkOrder o = new WorkOrder();
        o.id = ORDER;
        o.orderNo = "WB20261121001";
        o.elevatorId = "el_x";
        o.status = status;
        o.workerName = "张三";
        o.workerPlatformId = "pid_zhang";
        o.assistantName = "李四";
        o.assistantPlatformId = "pid_li";
        o.checkinTime = LocalDateTime.of(2026, 11, 21, 9, 30);
        o.checkinExtraJson = "{\"latitude\":29.6,\"longitude\":106.5,\"geoStatus\":\"PROVIDED\"}";
        o.assistantCheckinExtraJson = "{\"latitude\":29.7,\"longitude\":106.6,\"geoStatus\":\"ASSISTANT_EXEMPT\"}";
        o.regionId = "rg_yubei";
        return o;
    }

    @Test
    void releaseDoneOrderRejected() {
        when(orderMapper.selectById(ORDER)).thenReturn(claimed("DONE"));

        BizException e = assertThrows(BizException.class, () -> service.releaseToPool(ORDER, ACTOR));
        assertEquals(422, e.getCode(), "DONE 工单不可退回池（记录已上报监管平台）");
        verifyNoInteractions(auditLogService);
        verify(orderMapper, never()).update(any(), any());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void releaseUnclaimedIdempotent() {
        WorkOrder o = new WorkOrder();
        o.id = ORDER;
        o.status = "PENDING";
        o.regionId = "rg_yubei";
        when(orderMapper.selectById(ORDER)).thenReturn(o);

        Map<String, Object> r = service.releaseToPool(ORDER, ACTOR);

        assertEquals(Boolean.TRUE, r.get("ok"), "未认领 PENDING 幂等返回 ok");
        assertEquals(Boolean.FALSE, r.get("released"), "幂等路径不视为发生退回");
        assertEquals("PENDING", r.get("status"), "幂等不改状态");
        verifyNoInteractions(auditLogService);
        verify(orderMapper, never()).update(any(), any());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void releaseClaimedAuditsBeforeClearing() {
        when(orderMapper.selectById(ORDER)).thenReturn(claimed("PROCESSING"));
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        service.releaseToPool(ORDER, ACTOR);

        InOrder inOrder = inOrder(auditLogService, orderMapper);
        ArgumentCaptor<SysAuditLog> auditCap = ArgumentCaptor.forClass(SysAuditLog.class);
        // M12 审计先行：audit.append 必须先于 orderMapper.update（清库），同一事务内
        inOrder.verify(auditLogService).append(auditCap.capture());
        inOrder.verify(orderMapper).update(isNull(), any());
        SysAuditLog audit = auditCap.getValue();
        assertEquals("order:release-to-pool", audit.action);
        assertEquals(ORDER, audit.target);
        assertEquals(ACTOR, audit.actorId, "审计须含操作人");
        assertEquals("SUCCESS", audit.result);
        assertTrue(audit.beforeSnap.contains("checkinExtraJson"), "快照须含主维保签到留痕：" + audit.beforeSnap);
        assertTrue(audit.beforeSnap.contains("assistantCheckinExtraJson"), "快照须含配合人签到留痕：" + audit.beforeSnap);
        assertTrue(audit.beforeSnap.contains("pid_zhang"), "快照须含原认领人：" + audit.beforeSnap);
        assertTrue(audit.beforeSnap.contains("pid_li"), "快照须含原配合人：" + audit.beforeSnap);
        assertTrue(audit.beforeSnap.contains("29.6"), "快照须含签到定位留痕原文：" + audit.beforeSnap);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearedOrderReentersPool() {
        when(orderMapper.selectById(ORDER)).thenReturn(claimed("PROCESSING"));
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> r = service.releaseToPool(ORDER, ACTOR);

        assertEquals(Boolean.TRUE, r.get("released"));
        assertEquals("PENDING", r.get("status"), "清库后工单回 PENDING 重新入池");
        ArgumentCaptor<LambdaUpdateWrapper<WorkOrder>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), cap.capture());
        LambdaUpdateWrapper<WorkOrder> wrapper = cap.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("worker_name="), "清认领人姓名：" + sqlSet);
        assertTrue(sqlSet.contains("worker_platform_id="), "清认领人平台ID：" + sqlSet);
        assertTrue(sqlSet.contains("assistant_name="), "清配合人姓名：" + sqlSet);
        assertTrue(sqlSet.contains("assistant_platform_id="), "清配合人平台ID：" + sqlSet);
        assertTrue(sqlSet.contains("checkin_extra="), "清主维保签到留痕：" + sqlSet);
        assertTrue(sqlSet.contains("assistant_checkin_extra="), "清配合人签到留痕：" + sqlSet);
        assertTrue(sqlSet.contains("checkin_time="), "清签到时间：" + sqlSet);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PENDING"), "状态回 PENDING：" + sqlSet);
        long nullParams = wrapper.getParamNameValuePairs().values().stream()
                .filter(java.util.Objects::isNull).count();
        assertTrue(nullParams >= 7, "认领/签到/签名/动态码字段须显式 SET NULL（参数化 null）："
                + wrapper.getParamNameValuePairs());
        assertFalse(sqlSet.contains("region_id"), "regionId 快照不变（M10）：" + sqlSet);
        assertFalse(sqlSet.contains("plan_time"), "计划时间不得被清：" + sqlSet);
        // 读-改竞态守卫（Fix round 1）：清库 UPDATE 必须带 status<>DONE 条件，
        // 否则并发签退在「读 PENDING」与「清库」之间提交时会被覆写回 PENDING（第二条 2.6 上报）
        String where = wrapper.getSqlSegment();
        assertTrue(where.contains("status"), "清库须带 DONE 状态守卫：" + where);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("DONE"),
                "清库须带 status<>DONE 守卫：" + where);
    }

    @Test
    void concurrentDoneAbortRelease() {
        // 并发用例：管理员读到的还是 PROCESSING，工人签退事务在读与清库之间提交（status 已 DONE）。
        // 条件更新命中 0 行 → 抛 422 并整体回滚（审计一并回滚），不得覆写 DONE 抹掉签到/签名留痕。
        TxTestSupport.CapturingTx tx = TxTestSupport.capturingTx();
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        WorkOrderService svc = new WorkOrderService(orderMapper, mock(ElevatorMapper.class),
                mock(UseUnitMapper.class), mock(EmployeeMapper.class), mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class), mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), mock(EmployeeScopeService.class),
                mock(CheckinThresholdService.class), mock(FileStorageService.class), tx.template, props);
        svc.setAuditLogService(auditLogService);
        WorkOrder processing = claimed("PROCESSING");
        WorkOrder done = claimed("DONE");
        when(orderMapper.selectById(ORDER)).thenReturn(processing, done);
        when(orderMapper.update(isNull(), any())).thenReturn(0); // 守卫命中 0 行 = 竞态落败

        BizException e = assertThrows(BizException.class, () -> svc.releaseToPool(ORDER, ACTOR));

        assertEquals(422, e.getCode(), "并发签退已 DONE：退回必须 422 中止");
        assertTrue(tx.rolledBack, "竞态落败必须回滚事务（审计与清库不落）");
        assertFalse(tx.committed, "竞态落败不得提交");
        // 状态不被破坏：内存工单仍是原认领态，无任何字段被清
        assertEquals("PROCESSING", processing.status);
        assertEquals("pid_zhang", processing.workerPlatformId);
        assertTrue(processing.checkinExtraJson.contains("29.6"), "签到留痕不得被抹");
    }
}
