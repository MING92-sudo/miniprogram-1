package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientMigrationIssue;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ClientMigrationIssueMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 人工核对清单服务（client_migration_issue，spec §6.6 / §19-15；裁决 R2/R10 + T1 前瞻提示）：
 * resolve 执行顺序 = 全量前置校验 → 条件更新认领（WHERE resolution='PENDING'）→ 回填对象行 → 审计，
 * 校验失败 422 零副作用、并发第二次 422 且不重复回填；upsert 按 uk_issue 幂等且不触碰已有裁定；
 * list 返回 {list,total,pending}（pending=全局 PENDING 口径，不受筛选影响）。
 */
class MigrationIssueServiceTest {

    private ClientMigrationIssueMapper issueMapper;
    private MaintainRecordMapper recordMapper;
    private FaultMapper faultMapper;
    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private UseUnitMapper useUnitMapper;
    private AuditLogService audit;
    private MigrationIssueService svc;

    /** 条件更新的 LambdaUpdateWrapper.set 列解析需 TableInfo 缓存（RecordLifecycleTest 同款先例） */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), ClientMigrationIssue.class);
    }

    @BeforeEach
    void setUp() {
        issueMapper = mock(ClientMigrationIssueMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        faultMapper = mock(FaultMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        audit = mock(AuditLogService.class);
        svc = new MigrationIssueService(issueMapper, recordMapper, faultMapper, orderMapper,
                elevatorMapper, useUnitMapper, audit);
    }

    /** PENDING 问题行夹具：selectById 命中 + 条件更新默认命中 1 行 */
    private ClientMigrationIssue issue(String objectType, String objectId) {
        ClientMigrationIssue i = new ClientMigrationIssue();
        i.id = "mi1";
        i.objectType = objectType;
        i.objectId = objectId;
        i.issueType = "UNMATCHED";
        i.resolution = "PENDING";
        when(issueMapper.selectById("mi1")).thenReturn(i);
        when(issueMapper.update(any(), any())).thenReturn(1);
        return i;
    }

    private UseUnit unit() {
        UseUnit u = new UseUnit();
        u.id = "uu1";
        u.unitName = "启元物业";
        return u;
    }

    @Test
    void resolveBackfillsRecordRowWithUnitIdAndName() {
        issue("RECORD", "ur1");
        when(useUnitMapper.selectById("uu1")).thenReturn(unit());
        MaintainRecord saved = new MaintainRecord();
        saved.id = "ur1";
        when(recordMapper.selectById("ur1")).thenReturn(saved);
        Map<String, Object> out = svc.resolve("mi1", "BACKFILLED", "uu1", null, "已核对合同", "emp1");
        assertEquals("uu1", saved.useUnitId);
        assertEquals("启元物业", saved.useUnitName);
        verify(recordMapper).updateById(saved);
        assertEquals(true, out.get("ok"));
    }

    @Test
    void resolveTwiceSecondRejected422() {
        issue("RECORD", "ur1");
        when(useUnitMapper.selectById("uu1")).thenReturn(unit());
        MaintainRecord saved = new MaintainRecord();
        saved.id = "ur1";
        when(recordMapper.selectById("ur1")).thenReturn(saved);
        when(issueMapper.update(any(), any())).thenReturn(1).thenReturn(0);
        svc.resolve("mi1", "BACKFILLED", "uu1", null, "第一次", "emp1");
        BizException ex = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "BACKFILLED", "uu1", null, "第二次", "emp1"));
        assertEquals(422, ex.getCode());
        assertEquals("该问题已处理", ex.getMessage());
        verify(recordMapper, times(1)).updateById(saved); // 不重复回填（review focus #3）
        verify(audit, times(1)).append(any(SysAuditLog.class));
    }

    @Test
    void backfilledRequiresExistingUnit() {
        issue("RECORD", "ur1");
        when(useUnitMapper.selectById("uu404")).thenReturn(null);
        BizException missing = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "BACKFILLED", "uu404", null, "note", "emp1"));
        assertEquals(422, missing.getCode());
        BizException blank = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "BACKFILLED", " ", null, "note", "emp1"));
        assertEquals(422, blank.getCode());
        verify(issueMapper, never()).update(any(), any()); // R2：校验失败不留半成品
        verify(recordMapper, never()).updateById(any(MaintainRecord.class));
    }

    @Test
    void upsertUpdatesEvidenceWithoutTouchingResolution() {
        ClientMigrationIssue exist = new ClientMigrationIssue();
        exist.id = "mi1";
        exist.objectType = "RECORD";
        exist.objectId = "ur1";
        exist.issueType = "UNMATCHED";
        exist.evidence = "旧证据";
        exist.confidence = "LOW";
        exist.resolution = "MANUAL_DONE";
        exist.decisionJson = "{\"anchorUnitId\":\"uu1\"}";
        exist.resolveNote = "已带外处理";
        when(issueMapper.selectOne(any())).thenReturn(exist);
        svc.upsert("RECORD", "ur1", "UNMATCHED", "310101001", null, null, "复核仍未匹配", "MID");
        verify(issueMapper, never()).insert(any(ClientMigrationIssue.class));
        verify(issueMapper).updateById(exist);
        assertEquals("复核仍未匹配", exist.evidence);
        assertEquals("MID", exist.confidence);
        assertEquals("MANUAL_DONE", exist.resolution); // 不覆盖已有裁定
        assertEquals("{\"anchorUnitId\":\"uu1\"}", exist.decisionJson);
        assertEquals("已带外处理", exist.resolveNote);
    }

    @Test
    void resolveWritesAudit() {
        issue("FAULT", "f1");
        svc.resolve("mi1", "SKIPPED", null, null, "裁定不迁", "emp9");
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(audit).append(captor.capture());
        SysAuditLog a = captor.getValue();
        assertEquals("migration:resolve", a.action);
        assertEquals("mi1", a.target);
        assertEquals("SUCCESS", a.result);
        assertEquals("裁定不迁", a.reason);
        assertEquals("emp9", a.actorId);
    }

    /** T1 前瞻提示：created_at NOT NULL 无 DB 默认、@TableId 走全局 ASSIGN_ID——INSERT 必须显式 id/createdAt */
    @Test
    void upsertInsertsNewPendingIssueWithExplicitIdAndCreatedAt() {
        when(issueMapper.selectOne(any())).thenReturn(null);
        svc.upsert("ORDER", "wo1", "TRANSFER_SUSPECT", "310101001", null, "uu9", "电梯有已确认过户记录", "LOW");
        verify(issueMapper, never()).updateById(any(ClientMigrationIssue.class));
        verify(issueMapper).insert(argThat((ClientMigrationIssue row) ->
                row.id != null && row.id.startsWith("mi_")
                        && "PENDING".equals(row.resolution) && row.createdAt != null
                        && "ORDER".equals(row.objectType) && "wo1".equals(row.objectId)
                        && "TRANSFER_SUSPECT".equals(row.issueType)
                        && "310101001".equals(row.elevatorCode) && "uu9".equals(row.candidateUnitId)
                        && "电梯有已确认过户记录".equals(row.evidence) && "LOW".equals(row.confidence)));
    }

    @Test
    void resolveRejectsUnknownResolution422() {
        issue("RECORD", "ur1");
        BizException unknown = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "RESOLVED", null, null, "note", "emp1"));
        assertEquals(422, unknown.getCode());
        BizException nul = assertThrows(BizException.class,
                () -> svc.resolve("mi1", null, null, null, "note", "emp1"));
        assertEquals(422, nul.getCode());
        verify(issueMapper, never()).update(any(), any());
        verify(audit, never()).append(any(SysAuditLog.class));
    }

    @Test
    void backfilledRejectsAccountObjectType422() {
        issue("ACCOUNT", "u1");
        when(useUnitMapper.selectById("uu1")).thenReturn(unit());
        BizException ex = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "BACKFILLED", "uu1", null, "note", "emp1"));
        assertEquals(422, ex.getCode()); // ACCOUNT 类 P3 才有，不支持回填
        verify(issueMapper, never()).update(any(), any()); // 未认领零副作用
        // 对照：非 BACKFILLED 裁定不受对象类型限制（P3 迁移裁定走 SKIPPED/MANUAL_DONE）
        svc.resolve("mi1", "MANUAL_DONE", null, null, "已带外迁移", "emp1");
        verify(issueMapper).update(any(), any());
    }

    @Test
    void resolveBackfillMissingObjectRow422() {
        issue("ORDER", "wo404");
        when(useUnitMapper.selectById("uu1")).thenReturn(unit());
        when(orderMapper.selectById("wo404")).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> svc.resolve("mi1", "BACKFILLED", "uu1", null, "note", "emp1"));
        assertEquals(422, ex.getCode());
        verify(issueMapper, never()).update(any(), any()); // R2：前置校验全过才认领
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    /** 行为规格 4：decision != null → JsonUtil.write 存 decisionJson（P3 Runner 消费，本期只存不读） */
    @SuppressWarnings("unchecked")
    @Test
    void resolveStoresDecisionJsonForRunner() {
        issue("ACCOUNT", "u1");
        svc.resolve("mi1", "SKIPPED", null, Map.of("passwordSource", "EMP"), "口令异源待重跑", "emp1");
        ArgumentCaptor<Wrapper<ClientMigrationIssue>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ClientMigrationIssue> uw = (LambdaUpdateWrapper<ClientMigrationIssue>) captor.getValue();
        assertTrue(uw.getSqlSet().contains("decision_json"));
        assertTrue(uw.getParamNameValuePairs().containsValue("{\"passwordSource\":\"EMP\"}"));
    }

    /** R10：{list,total,pending}——total=筛选后总数（分页查询），pending=全局 PENDING（页头角标） */
    @SuppressWarnings("unchecked")
    @Test
    void listReturnsRowsTotalAndGlobalPending() {
        ClientMigrationIssue i = new ClientMigrationIssue();
        i.id = "mi1";
        i.issueType = "UNMATCHED";
        i.objectType = "RECORD";
        i.objectId = "ur1";
        i.elevatorCode = "310101001";
        i.resolution = "PENDING";
        i.decisionJson = "{\"k\":\"v\"}";
        i.createdAt = TimeUtil.parse("2026-11-17 09:00:00");
        Page<ClientMigrationIssue> p = new Page<>(1, 20);
        p.setRecords(List.of(i));
        p.setTotal(3);
        when(issueMapper.selectPage(any(), any())).thenReturn(p);
        when(issueMapper.selectCount(any())).thenReturn(7L);
        Map<String, Object> out = svc.list("UNMATCHED", null, 1, 20);
        assertEquals(3, out.get("total"));
        assertEquals(7, out.get("pending"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("mi1", row.get("id"));
        assertEquals("UNMATCHED", row.get("issueType"));
        assertEquals("RECORD", row.get("objectType"));
        assertEquals("ur1", row.get("objectId"));
        assertEquals("310101001", row.get("elevatorCode"));
        assertEquals("PENDING", row.get("resolution"));
        assertEquals("{\"k\":\"v\"}", row.get("decisionJson")); // 原样字符串
        assertEquals("2026-11-17 09:00:00", row.get("createdAt"));
        assertEquals("", row.get("resolvedAt")); // 未裁定时刻收敛空串
    }
}
