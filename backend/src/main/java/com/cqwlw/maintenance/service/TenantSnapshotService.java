package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租户快照收口服务（权限 P2 T3；spec §6 / plan Task 3/4/6）：
 * 派单/排班工单、急修单、签退记录创建时冻结 useUnitId/useUnitName，避免后续电梯过户改写归属。
 * 过户嫌疑口径：biz_elevator_change 存在 CONFIRMED 的 TRANSFER 行即命中。
 */
@Service
public class TenantSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(TenantSnapshotService.class);

    private final UseUnitMapper useUnitMapper;
    private final BizElevatorChangeMapper bizElevatorChangeMapper;
    /** plan T3 Consumes 明列，裁决 R3：T3 即注入，T4 的 applyRecord 消费 */
    private final MigrationIssueService migrationIssueService;

    public TenantSnapshotService(UseUnitMapper useUnitMapper,
                                 BizElevatorChangeMapper bizElevatorChangeMapper,
                                 MigrationIssueService migrationIssueService) {
        this.useUnitMapper = useUnitMapper;
        this.bizElevatorChangeMapper = bizElevatorChangeMapper;
        this.migrationIssueService = migrationIssueService;
    }

    /** 工单快照：useUnitId 直接继承电梯，useUnitName 按单位档案解析（未命中留 null）。 */
    public void applyOrder(WorkOrder o, Elevator el) {
        o.useUnitId = el.useUnitId;
        o.useUnitName = unitNameOf(el.useUnitId);
    }

    /** 急修单快照：与工单同语义。 */
    public void applyFault(Fault f, Elevator el) {
        f.useUnitId = el.useUnitId;
        f.useUnitName = unitNameOf(el.useUnitId);
    }

    /** 签退记录快照：继承工单快照；缺失时过户嫌疑置 NULL 留痕，否则回退电梯（权限 P2 T4，裁决 R3）。 */
    public void applyRecord(MaintainRecord r, WorkOrder o, Elevator el) {
        if (r == null) {
            return;
        }
        r.elevatorId = o != null ? o.elevatorId : (el == null ? null : el.id);
        if (o != null && o.useUnitId != null) {
            r.useUnitId = o.useUnitId;
            r.useUnitName = o.useUnitName;
            return;
        }
        if (el != null && isTransferSuspect(el.id)) {
            // 工单快照缺失且电梯有过户确认记录：两列保持 NULL，仅留 LOW 级问题，不阻断签退
            r.useUnitId = null;
            r.useUnitName = null;
            try {
                migrationIssueService.upsert("RECORD", r.id, "TRANSFER_SUSPECT", r.elevatorCode,
                        null, el.useUnitId, "工单快照缺失且电梯有过户确认记录", "LOW");
            } catch (Exception e) {
                log.warn("签退记录过户嫌疑留痕失败（不阻断签退）：{}", e.getMessage());
            }
            return;
        }
        String unitId = el == null ? null : el.useUnitId;
        r.useUnitId = unitId;
        r.useUnitName = unitNameOf(unitId);
        log.warn("签退记录 {} 工单快照缺失且电梯无过户嫌疑，回退电梯使用单位 {}（新链路不应发生）", r.id, unitId);
    }

    /** 过户嫌疑：存在 change_type='TRANSFER' AND status='CONFIRMED' AND elevator_id=? 即 true。 */
    public boolean isTransferSuspect(String elevatorId) {
        LambdaQueryWrapper<BizElevatorChange> w = new LambdaQueryWrapper<BizElevatorChange>()
                .eq(BizElevatorChange::getChangeType, "TRANSFER")
                .eq(BizElevatorChange::getStatus, "CONFIRMED")
                .eq(BizElevatorChange::getElevatorId, elevatorId);
        return bizElevatorChangeMapper.selectCount(w) > 0;
    }

    /** 单位名解析：null/blank 直接返回 null，未命中同样返回 null。 */
    public String unitNameOf(String useUnitId) {
        if (useUnitId == null || useUnitId.isBlank()) {
            return null;
        }
        UseUnit u = useUnitMapper.selectById(useUnitId);
        return u == null ? null : u.unitName;
    }
}
