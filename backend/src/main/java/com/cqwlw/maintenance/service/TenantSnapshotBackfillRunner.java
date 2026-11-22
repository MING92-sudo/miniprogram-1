package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TenantSnapshotBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSnapshotBackfillRunner.class);

    /** 每批上限，防大表一次性载入内存 */
    private static final String BATCH_LIMIT = "LIMIT 500";

    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final WorkOrderMapper orderMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final BizElevatorChangeMapper changeMapper;
    private final MigrationIssueService issues;

    public TenantSnapshotBackfillRunner(MaintainRecordMapper recordMapper, FaultMapper faultMapper,
                                        WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                                        UseUnitMapper useUnitMapper, BizElevatorChangeMapper changeMapper,
                                        MigrationIssueService issues) {
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.orderMapper = orderMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.changeMapper = changeMapper;
        this.issues = issues;
    }

    /** 启动期回填（先例 UnitAccountUnifyService.onStartup），日志输出计数。 */
    @PostConstruct
    public void onStartup() {
        Report r = backfill();
        log.info("租户快照存量回填：工单 {} / 记录 {} / 急修 {} / 问题 {}",
                r.orders, r.records, r.faults, r.issues);
    }

    /** 本次回填计数（orders/records/faults = 回填或留痕的对象数；issues = 写入的问题数）。 */
    public static class Report {
        public int orders;
        public int records;
        public int faults;
        public int issues;
    }

    /** 回填入口：嫌疑集 S 与单位名缓存仅加载一次，复用至本次调用的三类对象。 */
    public Report backfill() {
        Report rep = new Report();
        Set<String> suspects = loadSuspects();
        Map<String, String> unitNames = new HashMap<>();
        backfillOrders(rep, suspects, unitNames);
        backfillRecords(rep, suspects, unitNames);
        backfillFaults(rep, suspects, unitNames);
        return rep;
    }

    /** 嫌疑集 S：biz_elevator_change 中 change_type='TRANSFER' AND status='CONFIRMED' 的 elevator_id。 */
    private Set<String> loadSuspects() {
        List<BizElevatorChange> changes = changeMapper.selectList(new LambdaQueryWrapper<BizElevatorChange>()
                .eq(BizElevatorChange::getChangeType, "TRANSFER")
                .eq(BizElevatorChange::getStatus, "CONFIRMED"));
        Set<String> s = new HashSet<>();
        if (changes != null) {
            for (BizElevatorChange c : changes) {
                if (c != null && c.elevatorId != null) {
                    s.add(c.elevatorId);
                }
            }
        }
        return s;
    }

    // ---------- ORDER ----------

    private void backfillOrders(Report rep, Set<String> suspects, Map<String, String> unitNames) {
        String lastId = null;
        while (true) {
            LambdaQueryWrapper<WorkOrder> q = new LambdaQueryWrapper<WorkOrder>()
                    .isNull(WorkOrder::getUseUnitId);
            if (lastId != null) {
                q.gt(WorkOrder::getId, lastId);
            }
            List<WorkOrder> batch = orderMapper.selectList(q.orderByAsc(WorkOrder::getId).last(BATCH_LIMIT));
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (WorkOrder o : batch) {
                handleOrder(o, rep, suspects, unitNames);
            }
            lastId = batch.get(batch.size() - 1).id;
        }
    }

    private void handleOrder(WorkOrder o, Report rep, Set<String> suspects, Map<String, String> unitNames) {
        try {
            if (o == null) {
                return;
            }
            Elevator el = o.elevatorId == null ? null : elevatorMapper.selectById(o.elevatorId);
            if (el != null && suspects.contains(el.id)) {
                // S 内电梯：不回填，仅留 LOW 级人工核对
                issue("ORDER", o.id, "TRANSFER_SUSPECT", el.elevatorCode,
                        el.useUnitId, "工单对应电梯存在已确认过户记录，快照不自动回填", "LOW", rep);
            } else if (el != null) {
                WorkOrder patch = new WorkOrder();
                patch.id = o.id;
                patch.useUnitId = el.useUnitId;
                patch.useUnitName = unitName(el.useUnitId, unitNames);
                orderMapper.updateById(patch);
            } else {
                issue("ORDER", o.id, "UNMATCHED", null,
                        null, "工单 elevator_id 未匹配到电梯档案", "LOW", rep);
            }
            rep.orders++;
        } catch (Exception e) {
            log.warn("工单快照回填失败（继续处理下一行）: id={}, {}", o == null ? null : o.id, e.getMessage());
        }
    }

    // ---------- RECORD ----------

    private void backfillRecords(Report rep, Set<String> suspects, Map<String, String> unitNames) {
        String lastId = null;
        while (true) {
            LambdaQueryWrapper<MaintainRecord> q = new LambdaQueryWrapper<MaintainRecord>()
                    .isNull(MaintainRecord::getUseUnitId);
            if (lastId != null) {
                q.gt(MaintainRecord::getId, lastId);
            }
            List<MaintainRecord> batch = recordMapper.selectList(q.orderByAsc(MaintainRecord::getId).last(BATCH_LIMIT));
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (MaintainRecord r : batch) {
                handleRecord(r, rep, suspects, unitNames);
            }
            lastId = batch.get(batch.size() - 1).id;
        }
    }

    private void handleRecord(MaintainRecord r, Report rep, Set<String> suspects, Map<String, String> unitNames) {
        try {
            if (r == null) {
                return;
            }
            // 1) 优先继承工单快照（一次性迁移允许逐行查）
            if (r.workOrderId != null && !r.workOrderId.isBlank()) {
                WorkOrder o = orderMapper.selectById(r.workOrderId);
                if (o != null && o.useUnitId != null && !o.useUnitId.isBlank()) {
                    MaintainRecord patch = new MaintainRecord();
                    patch.id = r.id;
                    patch.useUnitId = o.useUnitId;
                    patch.useUnitName = o.useUnitName;
                    patch.elevatorId = o.elevatorId; // R4：工单继承路径取 o.elevatorId
                    recordMapper.updateById(patch);
                    rep.records++;
                    return;
                }
            }
            // 2) 无工单快照：按 elevator_code 查电梯（ORDER BY id 确定性）
            List<Elevator> els = elevatorsByCode(r.elevatorCode);
            if (els.isEmpty()) {
                issue("RECORD", r.id, "UNMATCHED", r.elevatorCode,
                        null, "记录电梯编码未匹配到电梯档案", "LOW", rep);
                rep.records++;
                return;
            }
            Elevator first = els.get(0);
            if (suspects.contains(first.id)) {
                issue("RECORD", r.id, "TRANSFER_SUSPECT", r.elevatorCode,
                        first.useUnitId, "记录电梯存在已确认过户记录，快照不自动回填", "LOW", rep);
                rep.records++;
                return;
            }
            MaintainRecord patch = new MaintainRecord();
            patch.id = r.id;
            patch.useUnitId = first.useUnitId;
            patch.useUnitName = unitName(first.useUnitId, unitNames);
            patch.elevatorId = first.id; // R4：电梯码路径取首行 el.id
            recordMapper.updateById(patch);
            if (els.size() > 1) {
                issue("RECORD", r.id, "CODE_AMBIGUOUS", r.elevatorCode,
                        first.useUnitId, "同电梯编码匹配多行电梯，按 id 取首行回填", "MID", rep);
            }
            rep.records++;
        } catch (Exception e) {
            log.warn("维保记录快照回填失败（继续处理下一行）: id={}, {}", r == null ? null : r.id, e.getMessage());
        }
    }

    // ---------- FAULT ----------

    private void backfillFaults(Report rep, Set<String> suspects, Map<String, String> unitNames) {
        String lastId = null;
        while (true) {
            LambdaQueryWrapper<Fault> q = new LambdaQueryWrapper<Fault>()
                    .isNull(Fault::getUseUnitId);
            if (lastId != null) {
                q.gt(Fault::getId, lastId);
            }
            List<Fault> batch = faultMapper.selectList(q.orderByAsc(Fault::getId).last(BATCH_LIMIT));
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (Fault f : batch) {
                handleFault(f, rep, suspects, unitNames);
            }
            lastId = batch.get(batch.size() - 1).id;
        }
    }

    private void handleFault(Fault f, Report rep, Set<String> suspects, Map<String, String> unitNames) {
        try {
            if (f == null) {
                return;
            }
            List<Elevator> els = elevatorsByCode(f.elevatorCode);
            if (els.isEmpty()) {
                issue("FAULT", f.id, "UNMATCHED", f.elevatorCode,
                        null, "急修单电梯编码未匹配到电梯档案", "LOW", rep);
                rep.faults++;
                return;
            }
            Elevator first = els.get(0);
            if (suspects.contains(first.id)) {
                issue("FAULT", f.id, "TRANSFER_SUSPECT", f.elevatorCode,
                        first.useUnitId, "急修单电梯存在已确认过户记录，快照不自动回填", "LOW", rep);
                rep.faults++;
                return;
            }
            Fault patch = new Fault();
            patch.id = f.id;
            patch.useUnitId = first.useUnitId;
            patch.useUnitName = unitName(first.useUnitId, unitNames);
            faultMapper.updateById(patch);
            if (els.size() > 1) {
                issue("FAULT", f.id, "CODE_AMBIGUOUS", f.elevatorCode,
                        first.useUnitId, "同电梯编码匹配多行电梯，按 id 取首行回填", "MID", rep);
            }
            rep.faults++;
        } catch (Exception e) {
            log.warn("急修单快照回填失败（继续处理下一行）: id={}, {}", f == null ? null : f.id, e.getMessage());
        }
    }

    // ---------- helpers ----------

    /** 按电梯编码查电梯并按 id 升序（同码多行确定性取首行）。 */
    private List<Elevator> elevatorsByCode(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        List<Elevator> els = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, code).orderByAsc(Elevator::getId));
        return els == null ? List.of() : els;
    }

    /** 单位名解析：null/blank 返回 null，未命中同样 null；本次调用内缓存（含 null 值）。 */
    private String unitName(String useUnitId, Map<String, String> cache) {
        if (useUnitId == null || useUnitId.isBlank()) {
            return null;
        }
        if (cache.containsKey(useUnitId)) {
            return cache.get(useUnitId);
        }
        UseUnit u = useUnitMapper.selectById(useUnitId);
        String name = u == null ? null : u.unitName;
        cache.put(useUnitId, name);
        return name;
    }

    /** 统一留痕入口：8 参逐字填，currentUnitId 恒 null（迁移前）。 */
    private void issue(String objectType, String objectId, String issueType, String elevatorCode,
                       String candidateUnitId, String evidence, String confidence, Report rep) {
        issues.upsert(objectType, objectId, issueType, elevatorCode, null, candidateUnitId, evidence, confidence);
        rep.issues++;
    }
}
