package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动派单（用户确认口径 2026-09-30）：
 * 到期前一天自动把名下全部到期电梯一次性派给对应维保人员（同项目同日到期 → 当日一次性全部 09:00 派单）；
 * 保养类型按时间自动升级（TSG 附件累加式：OY 365 / SM 180 / TM 90 / HM 15）；
 * 派单同时写入消息中心。真实后端为定时任务 + 业务触达时即时检查（与 mock 行为对齐）。
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper orderMapper;
    private final MessageMapper messageMapper;
    private final ChecklistService checklistService;

    public DispatchService(ElevatorMapper elevatorMapper, WorkOrderMapper orderMapper,
                           MessageMapper messageMapper, ChecklistService checklistService) {
        this.elevatorMapper = elevatorMapper;
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.checklistService = checklistService;
    }

    /** 每日 09:00 定时派单（scripts/verify-dispatch.js 验收口径） */
    @Scheduled(cron = "0 0 9 * * ?")
    public void scheduledDispatch() {
        int n = ensureDueOrders().size();
        log.info("定时派单完成: 新建 {} 单", n);
    }

    public List<WorkOrder> ensureDueOrders() {
        List<WorkOrder> created = new ArrayList<>();
        long now = TimeUtil.toMillis(TimeUtil.now());
        for (Elevator el : elevatorMapper.selectList(null)) {
            if (el.workTypeCode == null || el.workTypeCode.isEmpty()) {
                continue;
            }
            Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
            if (active != null && active > 0) {
                continue; // 一次只派一单
            }
            LocalDateTime last = lastMaintenance(el.id, null);
            if (last == null) {
                last = el.lastMaintenanceAt;
            }
            if (last == null) {
                continue;
            }
            int intervalDays = el.intervalDays != null ? el.intervalDays : 15;
            long dueMs = TimeUtil.toMillis(last) + intervalDays * 86400000L;
            if (now < dueMs - 86400000L) {
                continue; // 未到"到期前一天"
            }
            String code = "HM";
            if (elapsedDays(lastOf(el.id, "OY", last), now) >= 365) {
                code = "OY";
            } else if (elapsedDays(lastOf(el.id, "SM", last), now) >= 180) {
                code = "SM";
            } else if (elapsedDays(lastOf(el.id, "TM", last), now) >= 90) {
                code = "TM";
            }
            // 计划时间 = 到期日当天 09:00（已过期则记为今日，前端统计呈"保养超期"）
            String dueDay = TimeUtil.date(TimeUtil.fromMillis(dueMs));
            String todayStr = TimeUtil.date(TimeUtil.now());
            String planDay = dueDay.compareTo(todayStr) >= 0 ? dueDay : todayStr;
            created.add(createOrder(el, code, TimeUtil.parse(planDay + " 09:00:00"),
                    "自动派单通知", "已到维保周期（上次维保 "
                    + TimeUtil.format(last).substring(0, 10) + "）", true));
        }
        return created;
    }

    /** 生成工单 + 消息中心通知（自动/手动派单共用） */
    private WorkOrder createOrder(Elevator el, String code, LocalDateTime planTime,
                                  String msgTitle, String msgReason, boolean auto) {
        WorkOrder o = new WorkOrder();
        o.id = Ids.next("wo");
        o.orderNo = "WO" + TimeUtil.date(TimeUtil.now()).replace("-", "") + "-"
                + String.format("%03d", count() + 1);
        o.elevatorId = el.id;
        o.workType = checklistService.label(code);
        o.workTypeCode = code;
        o.planTime = planTime;
        o.status = "PENDING";
        o.workerName = el.workerName;
        o.assistantName = el.assistantName == null ? "" : el.assistantName;
        o.workerPlatformId = el.workerPlatformId;
        o.assistantPlatformId = el.assistantPlatformId;
        o.autoDispatched = auto;
        o.checklistJson = com.cqwlw.maintenance.util.JsonUtil.write(
                checklistService.buildChecklist(code, el.category));
        orderMapper.insert(o);
        Message msg = new Message();
        msg.id = Ids.next("msg");
        msg.title = msgTitle;
        msg.content = el.elevatorName + " " + o.workType + msgReason + "，按绑定关系派给 "
                + el.workerName + "，请及时扫码签到。";
        msg.createdAt = TimeUtil.now();
        msg.readFlag = false;
        messageMapper.insert(msg);
        return o;
    }

    /** 手动派单（首保/补单）：跳过到期检查，按电梯当前绑定的维保人员立即生成工单（用户需求：无首次维保时间的电梯需手动派单） */
    public WorkOrder dispatchNow(String elevatorId) {
        Elevator el = elevatorMapper.selectById(elevatorId);
        if (el == null) {
            throw new com.cqwlw.maintenance.common.BizException(1404, "电梯不存在");
        }
        if (el.workTypeCode == null || el.workTypeCode.isEmpty()) {
            throw new com.cqwlw.maintenance.common.BizException(422, "请先设置维保周期码");
        }
        if (el.workerName == null || el.workerName.isEmpty() || el.workerPlatformId == null
                || el.workerPlatformId.isEmpty()) {
            throw new com.cqwlw.maintenance.common.BizException(422,
                    "请先在电梯档案中分配维保人员（含平台ID）");
        }
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            throw new com.cqwlw.maintenance.common.BizException(422, "该电梯已有进行中的工单，不可重复派单");
        }
        return createOrder(el, el.workTypeCode, TimeUtil.parse(TimeUtil.date(TimeUtil.now()) + " 09:00:00"),
                "手动派单通知", "首保/手动派单", false);
    }

    private long count() {
        Long c = orderMapper.selectCount(null);
        return c == null ? 0 : c;
    }

    private LocalDateTime lastMaintenance(String elevatorId, String workTypeCode) {
        LambdaQueryWrapper<WorkOrder> q = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevatorId)
                .eq(WorkOrder::getStatus, "DONE")
                .isNotNull(WorkOrder::getCheckoutTime);
        if (workTypeCode != null) {
            q.eq(WorkOrder::getWorkTypeCode, workTypeCode);
        }
        return orderMapper.selectList(q).stream()
                .map(o -> o.checkoutTime)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDateTime lastOf(String elevatorId, String code, LocalDateTime fallback) {
        LocalDateTime t = lastMaintenance(elevatorId, code);
        return t != null ? t : fallback;
    }

    private long elapsedDays(LocalDateTime from, long nowMs) {
        if (from == null) {
            return 0;
        }
        return (nowMs - TimeUtil.toMillis(from)) / 86400000L;
    }
}
