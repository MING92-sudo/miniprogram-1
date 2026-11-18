package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ClientMigrationIssue;
import com.cqwlw.maintenance.entity.Fault;
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
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工核对清单服务（client_migration_issue，V47；spec §6.6 / §19-15）：
 * upsert 按 uk_issue 幂等留痕（Runner 重跑安全），list 分页投影，resolve 单条人工裁定。
 * 不提供批量 resolve（§6.6 硬约束）；只 INSERT/UPDATE，禁止 DELETE（留存红线）。
 * resolve 顺序（裁决 R2）：全量前置校验 → 条件更新认领（WHERE resolution='PENDING'，
 * 受影响 0 行=422 已处理）→ BACKFILLED 回填对象行快照列 → 审计；校验失败一律 422 零副作用。
 */
@Service
public class MigrationIssueService {

    private static final Logger log = LoggerFactory.getLogger(MigrationIssueService.class);

    /** 允许的人工裁定值（初始态 PENDING 不是裁定值；V47 列默认 'PENDING'） */
    private static final Set<String> RESOLUTIONS = Set.of("BACKFILLED", "SKIPPED", "MANUAL_DONE");
    /** 支持 BACKFILLED 回填的对象类型；ACCOUNT 类 issue P3 才出现，P2 裁定走 SKIPPED/MANUAL_DONE */
    private static final Set<String> BACKFILL_OBJECT_TYPES = Set.of("RECORD", "FAULT", "ORDER");

    private final ClientMigrationIssueMapper issueMapper;
    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final WorkOrderMapper orderMapper;
    /** plan T2 依赖清单要求（构造注入 7 个依赖）；本任务行为规格无消费路径，注入备核对视图扩展 */
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final AuditLogService auditLogService;

    public MigrationIssueService(ClientMigrationIssueMapper issueMapper, MaintainRecordMapper recordMapper,
                                 FaultMapper faultMapper, WorkOrderMapper orderMapper,
                                 ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper,
                                 AuditLogService auditLogService) {
        this.issueMapper = issueMapper;
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.orderMapper = orderMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 幂等留痕（spec §6）：按 uk_issue(objectType,objectId,issueType) 判存在——
     * 无则 INSERT（resolution=PENDING；显式 id/createdAt，T1 审查前瞻提示：created_at NOT NULL
     * 无 DB 默认、@TableId 走全局 ASSIGN_ID，不得依赖自动填充）；
     * 有则仅更新 evidence/confidence，不覆盖已有人工裁定（resolution/decisionJson/resolveNote 等）。
     */
    public void upsert(String objectType, String objectId, String issueType, String elevatorCode,
                       String currentUnitId, String candidateUnitId, String evidence, String confidence) {
        ClientMigrationIssue exist = issueMapper.selectOne(new LambdaQueryWrapper<ClientMigrationIssue>()
                .eq(ClientMigrationIssue::getObjectType, objectType)
                .eq(ClientMigrationIssue::getObjectId, objectId)
                .eq(ClientMigrationIssue::getIssueType, issueType)
                .last("LIMIT 1"));
        if (exist != null) {
            issueMapper.update(null, new LambdaUpdateWrapper<ClientMigrationIssue>()
                    .eq(ClientMigrationIssue::getId, exist.id)
                    .set(ClientMigrationIssue::getEvidence, evidence)
                    .set(ClientMigrationIssue::getConfidence, confidence));
            return;
        }
        ClientMigrationIssue row = new ClientMigrationIssue();
        row.id = Ids.next("mi");
        row.objectType = objectType;
        row.objectId = objectId;
        row.issueType = issueType;
        row.elevatorCode = elevatorCode;
        row.currentUnitId = currentUnitId;
        row.candidateUnitId = candidateUnitId;
        row.evidence = evidence;
        row.confidence = confidence;
        row.resolution = "PENDING";
        row.createdAt = TimeUtil.now();
        issueMapper.insert(row);
    }

    /**
     * 分页列表（管理端核对视图，spec §6.6）：issueType/resolution 筛选，SQL 分页（LIMIT 下推）。
     * 裁决 R10：total=筛选后总数；pending=全局 PENDING 总数（页头角标语义，不受筛选影响）。
     */
    public Map<String, Object> list(String issueType, String resolution, int page, int size) {
        LambdaQueryWrapper<ClientMigrationIssue> w = new LambdaQueryWrapper<>();
        if (issueType != null && !issueType.isBlank()) {
            w.eq(ClientMigrationIssue::getIssueType, issueType);
        }
        if (resolution != null && !resolution.isBlank()) {
            w.eq(ClientMigrationIssue::getResolution, resolution);
        }
        w.orderByDesc(ClientMigrationIssue::getCreatedAt);
        Page<ClientMigrationIssue> p = issueMapper.selectPage(new Page<>(page, size), w);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ClientMigrationIssue i : p.getRecords()) {
            rows.add(rowView(i));
        }
        Long pending = issueMapper.selectCount(new LambdaQueryWrapper<ClientMigrationIssue>()
                .eq(ClientMigrationIssue::getResolution, "PENDING"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", (int) p.getTotal());
        out.put("pending", pending == null ? 0 : pending.intValue());
        return out;
    }

    /**
     * 单条人工裁定（spec §6.6；执行顺序=裁决 R2）：
     * ①全量前置校验——resolution ∈ {BACKFILLED,SKIPPED,MANUAL_DONE}；BACKFILLED 必须带
     * backfillUnitId 且 use_unit 命中、对象类型 ∈ {RECORD,FAULT,ORDER}（ACCOUNT 类 422，P3 才有）、
     * 对象行命中——任一失败 422 零副作用；
     * ②条件更新认领 issue（eq id + eq resolution='PENDING'，set 五字段；受影响 0 行 →
     * 422「该问题已处理」，双击/并发第二次在此被拒且不重复回填）；
     * ③BACKFILLED 回填对象行 useUnitId/useUnitName（updateById）；
     * ④审计（migration:resolve）。decision 非空时 JsonUtil.write 存 decisionJson（P3 Runner 消费，本期只存不读）。
     */
    @Transactional
    public Map<String, Object> resolve(String id, String resolution, String backfillUnitId,
                                       Map<String, Object> decision, String note, String actorEmpId) {
        if (resolution == null || !RESOLUTIONS.contains(resolution)) {
            throw new BizException(422, "裁定值不合法：仅支持 BACKFILLED/SKIPPED/MANUAL_DONE");
        }
        ClientMigrationIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BizException(422, "核对问题不存在");
        }
        UseUnit unit = null;
        Object target = null;
        if ("BACKFILLED".equals(resolution)) {
            if (backfillUnitId == null || backfillUnitId.isBlank()) {
                throw new BizException(422, "BACKFILLED 必须指定回填单位");
            }
            unit = useUnitMapper.selectById(backfillUnitId);
            if (unit == null) {
                throw new BizException(422, "回填单位不存在");
            }
            if (!BACKFILL_OBJECT_TYPES.contains(issue.objectType)) {
                throw new BizException(422, "BACKFILLED 仅支持 RECORD/FAULT/ORDER 对象");
            }
            target = switch (issue.objectType) {
                case "RECORD" -> recordMapper.selectById(issue.objectId);
                case "FAULT" -> faultMapper.selectById(issue.objectId);
                default -> orderMapper.selectById(issue.objectId);
            };
            if (target == null) {
                throw new BizException(422, "回填对象行不存在");
            }
        }
        int affected = issueMapper.update(null, new LambdaUpdateWrapper<ClientMigrationIssue>()
                .eq(ClientMigrationIssue::getId, id)
                .eq(ClientMigrationIssue::getResolution, "PENDING")
                .set(ClientMigrationIssue::getResolution, resolution)
                .set(ClientMigrationIssue::getDecisionJson, decision == null ? null : JsonUtil.write(decision))
                .set(ClientMigrationIssue::getResolveNote, note)
                .set(ClientMigrationIssue::getResolvedBy, actorEmpId)
                .set(ClientMigrationIssue::getResolvedAt, TimeUtil.now()));
        if (affected == 0) {
            throw new BizException(422, "该问题已处理");
        }
        if (target instanceof MaintainRecord r) {
            r.useUnitId = backfillUnitId;
            r.useUnitName = unit.unitName;
            recordMapper.updateById(r);
        } else if (target instanceof Fault f) {
            f.useUnitId = backfillUnitId;
            f.useUnitName = unit.unitName;
            faultMapper.updateById(f);
        } else if (target instanceof WorkOrder o) {
            o.useUnitId = backfillUnitId;
            o.useUnitName = unit.unitName;
            orderMapper.updateById(o);
        }
        appendAudit(actorEmpId, id, note);
        return Map.of("ok", true);
    }

    /** 行视图：client_migration_issue 全部列；decisionJson 原样字符串（管理端/P3 Runner 自行解析） */
    private Map<String, Object> rowView(ClientMigrationIssue i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id);
        m.put("issueType", i.issueType);
        m.put("objectType", i.objectType);
        m.put("objectId", i.objectId);
        m.put("elevatorCode", i.elevatorCode);
        m.put("currentUnitId", i.currentUnitId);
        m.put("candidateUnitId", i.candidateUnitId);
        m.put("evidence", i.evidence);
        m.put("confidence", i.confidence);
        m.put("resolution", i.resolution);
        m.put("decisionJson", i.decisionJson);
        m.put("resolveNote", i.resolveNote);
        m.put("resolvedBy", i.resolvedBy);
        m.put("resolvedAt", TimeUtil.format(i.resolvedAt));
        m.put("createdAt", TimeUtil.format(i.createdAt));
        return m;
    }

    /** 审计留痕（spec §16：人工 resolve 全部写审计）；落库失败吞异常，不影响已完成的裁定（AuditLogService 调用方约定） */
    private void appendAudit(String actorEmpId, String issueId, String note) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.actorId = actorEmpId;
            a.action = "migration:resolve";
            a.target = issueId;
            a.result = "SUCCESS";
            a.reason = note;
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            log.warn("迁移核对审计落库失败（不影响主流程）: {}", ex.getMessage());
        }
    }
}
