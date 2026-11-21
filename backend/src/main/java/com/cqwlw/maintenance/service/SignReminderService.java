package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 签字到期提醒（V56，docs/04 V2.80）：定时扫描「待使用单位签署」记录，向客户门户推送消息。
 * 两档各至多一条（sign_reminded_at 去重）：
 *   临期——剩余 ≤ SLA 的 1/3（默认 72h → 24h）时推送「签字临期提醒」；
 *   超期——超过截止时间推送「签字超期提醒」。
 * 无单位快照（useUnitId 空）的记录无受众，跳过；推送只写消息表，不触碰记录状态机与平台上报。
 */
@Service
public class SignReminderService {

    private static final Logger log = LoggerFactory.getLogger(SignReminderService.class);
    private static final int SCAN_LIMIT = 500;

    private final MaintainRecordMapper recordMapper;
    private final MessageMapper messageMapper;
    private final ClientSignSlaService slaService;

    public SignReminderService(MaintainRecordMapper recordMapper, MessageMapper messageMapper,
                               ClientSignSlaService slaService) {
        this.recordMapper = recordMapper;
        this.messageMapper = messageMapper;
        this.slaService = slaService;
    }

    /** 每 30 分钟扫描；zone 必须显式指定（同 DispatchService 的容器 UTC 教训） */
    @Scheduled(cron = "0 */30 * * * ?", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public void sweep() {
        int n = sweepOnce();
        if (n > 0) {
            log.info("签字到期提醒推送: {} 条", n);
        }
    }

    /** 扫描并推送一轮，返回新推送条数（可重复执行，sign_reminded_at 幂等去重） */
    @Transactional(rollbackFor = Exception.class)
    public int sweepOnce() {
        LocalDateTime now = TimeUtil.now();
        int slaHours = slaService.slaHours(); // 整轮取一次，避免逐行重查 sys_config（N+1）
        // 临期阈值 = SLA 的 1/3（72h → 24h），不足 1 小时按 1 小时
        long nearMs = Math.max(1, slaHours / 3) * 3600000L;
        List<MaintainRecord> pending = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .orderByAsc(MaintainRecord::getCreatedAt)
                .last("LIMIT " + SCAN_LIMIT));
        int pushed = 0;
        for (MaintainRecord r : pending == null ? List.<MaintainRecord>of() : pending) {
            if (r.useUnitId == null || r.useUnitId.isBlank()) {
                continue; // 无单位快照 → 无客户受众
            }
            LocalDateTime deadline = slaService.deadlineOf(r, slaHours);
            if (deadline == null) {
                continue;
            }
            boolean overdue = now.isAfter(deadline);
            boolean nearDue = !overdue && deadline.atZone(TimeUtil.ZONE).toInstant().toEpochMilli()
                    - now.atZone(TimeUtil.ZONE).toInstant().toEpochMilli() <= nearMs;
            if (!overdue && !nearDue) {
                continue;
            }
            // 去重：临期档要求从未提醒；超期档要求上次提醒发生在截止之前（即超期后未提醒过）
            if (r.signRemindedAt != null && (overdue ? !r.signRemindedAt.isBefore(deadline) : true)) {
                continue;
            }
            // 守卫式最小更新（confirmAs 状态撕裂教训）：只盖 sign_reminded_at 且要求记录仍在
            // 待签状态——扫描间隙被客户签署/拒签的记录命中 0，整实体 updateById 会把 CONFIRMED
            // 回写成 PENDING，此处既不回写任何状态也不再推送消息。
            int stamped = recordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                    .eq(MaintainRecord::getId, r.id)
                    .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                    .eq(MaintainRecord::getConfirmStatus, "PENDING")
                    .set(MaintainRecord::getSignRemindedAt, now));
            if (stamped == 0) {
                continue;
            }
            r.signRemindedAt = now; // 内存镜像落库结果（同轮去重判断用；危险的是整实体回写，非本地赋值）
            Message msg = new Message();
            msg.id = Ids.next("msg");
            msg.useUnitId = r.useUnitId;
            if (overdue) {
                msg.title = "签字超期提醒";
                msg.content = (r.elevatorName == null ? r.elevatorCode : r.elevatorName) + " "
                        + (r.workType == null ? "维保" : r.workType)
                        + "记录已超过签字截止时间（" + TimeUtil.format(deadline) + "），请尽快到「待办签字」完成确认。";
            } else {
                msg.title = "签字临期提醒";
                msg.content = (r.elevatorName == null ? r.elevatorCode : r.elevatorName) + " "
                        + (r.workType == null ? "维保" : r.workType)
                        + "记录将于 " + TimeUtil.format(deadline) + " 到达签字截止时间，请及时到「待办签字」完成确认。";
            }
            msg.createdAt = now;
            msg.readFlag = false;
            messageMapper.insert(msg);
            pushed++;
        }
        return pushed;
    }
}
