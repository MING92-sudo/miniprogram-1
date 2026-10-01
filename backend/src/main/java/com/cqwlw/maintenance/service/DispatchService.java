package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动派单（用户确认口径 2026-09-30）：
 * 到期前一天自动把名下全部到期电梯一次性派给对应维保人员（同项目同日到期 → 当日一次性全部 09:00 派单）；
 * 保养类型按时间自动升级（TSG 附件累加式：OY 365 / SM 180 / TM 90 / HM 15）；
 * 派单同时写入消息中心。真实后端仅由定时任务驱动（每日 09:00 + 每 13 分钟兜底），
 * 读接口一律不触发派单（docs/04 A.10.9）。
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper orderMapper;
    private final MessageMapper messageMapper;
    private final EmployeeMapper employeeMapper;
    private final ChecklistService checklistService;

    public DispatchService(ElevatorMapper elevatorMapper, WorkOrderMapper orderMapper,
                           MessageMapper messageMapper, EmployeeMapper employeeMapper,
                           ChecklistService checklistService) {
        this.elevatorMapper = elevatorMapper;
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.employeeMapper = employeeMapper;
        this.checklistService = checklistService;
    }

    /** 每日 09:00 定时派单（scripts/verify-dispatch.js 验收口径）；zone 必须显式指定，
     *  否则容器（JVM 默认 UTC）会在北京时间 17:00 才触发，违反 AGENTS §5 V1.1 验收口径 */
    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public void scheduledDispatch() {
        int n = ensureDueOrders().size();
        log.info("定时派单完成: 新建 {} 单", n);
    }

    /**
     * 派单兜底自愈：容器在 09:00 未运行（重启/发布/休眠）导致漏派时，每 13 分钟补一次。
     * 派单判定本身幂等（该电梯已有未完成工单即跳过），故可安全重复执行。
     * 刻意**不**挂在 GET 读接口上：读请求不应产生写副作用，否则并发打开首页即可
     * 触发重复派单与"接口偶发 500"（B6/B7）。
     */
    @Scheduled(cron = "0 */13 * * * ?", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public void topUpDispatch() {
        int n = ensureDueOrders().size();
        if (n > 0) {
            log.info("派单兜底补派: 新建 {} 单", n);
        }
    }

    /** 调度入口以自调用方式调用本方法，不经过 Spring 代理，故两个 @Scheduled 方法上
     *  必须各自声明 @Transactional，否则此处注解失效、行锁在自动提交下立即释放 */
    @Transactional(rollbackFor = Exception.class)
    public List<WorkOrder> ensureDueOrders() {
        List<WorkOrder> created = new ArrayList<>();
        long now = TimeUtil.toMillis(TimeUtil.now());
        for (Elevator row : elevatorMapper.selectList(null)) {
            if (!maybeDue(row, now)) {
                continue;
            }
            // 同一台电梯的派单判定与写入串行化：SELECT ... FOR UPDATE 持有行锁，
            // 杜绝"两个线程同时读到无未完成工单 → 各插一张工单"的重复派单
            Elevator locked = lockElevator(row.getId());
            if (locked == null) {
                continue;
            }
            WorkOrder created1 = createOrderIfDue(locked, now);
            if (created1 != null) {
                created.add(created1);
            }
        }
        return created;
    }

    private Elevator lockElevator(String elevatorId) {
        return elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getId, elevatorId).last("FOR UPDATE"));
    }

    /**
     * 维保基准时间（= 上次维保时间）：优先取本地已完成工单的签退时间，其次取档案登记的
     * last_maintenance_at。两者皆空表示该梯**尚无任何维保基准**。
     */
    private LocalDateTime baseline(Elevator el) {
        LocalDateTime last = lastMaintenance(el.id, null);
        return last != null ? last : el.lastMaintenanceAt;
    }

    /** 无需上锁的快速判断：配置缺失、未绑定维保、尚有未完成工单、未到"到期前一天" */
    private boolean maybeDue(Elevator el, long now) {
        if (el.workTypeCode == null || el.workTypeCode.isEmpty()) {
            return false;
        }
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            return false; // 一次只派一单
        }
        LocalDateTime last = baseline(el);
        if (last == null) {
            // 无基准 → 放行首单。平台不提供上次维保时间（docs/06 #1 仍在索要），
            // 按既定口径「以我们第一次派单的维保时间为准」，故不能因缺基准而永久不派单。
            return true;
        }
        long dueMs = TimeUtil.toMillis(last) + intervalDays(el) * 86400000L;
        return now >= dueMs - 86400000L;
    }

    private static int intervalDays(Elevator el) {
        return el.intervalDays != null ? el.intervalDays : 15;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 校验电梯上的人员绑定确实对应一名**已备案的真实员工**：姓名与 platform_id 必须同时匹配。
     * 任一为空、查无此人、或姓名与该 platform_id 登记的姓名不符，均视为未备案。
     * 严禁退化为只按姓名匹配（docs/04 B.4：姓名会重名）。
     */
    private boolean identityOnFile(String name, String platformId) {
        if (isBlank(name) || isBlank(platformId)) {
            return false;
        }
        Employee e = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPlatformId, platformId.trim()).last("LIMIT 1"));
        return e != null && name.trim().equals(e.name == null ? "" : e.name.trim());
    }

    /** 在锁内重新判定并创建工单；不满足条件返回 null */
    private WorkOrder createOrderIfDue(Elevator el, long now) {
        // 必须在锁内重查未完成工单：maybeDue 的判断发生在加锁之前，并发跑批时两个线程
        // 可能都判定为"可派"，随后各插一张工单（同一梯重复派单 → 两条 2.6 上报）
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            return null;
        }
        LocalDateTime last = baseline(el);
        boolean firstRun = last == null;
        if (firstRun) {
            // 首单：无基准则以本次派单时刻起算，并作为后续周期的基准（用户口径：
            // 平台不提供上次维保时间时，以我们第一次派单的维保时间为准）
            last = TimeUtil.fromMillis(now);
        }
        // 派单门禁：**姓名与 platform_id 必须同时匹配上已备案的真实员工**才允许派单。
        // 只校验 platform_id 非空是不够的——电梯上若残留他人的 ID 配他人的姓名，会把他人
        // 的作业派到自己名下，2.6 的 workMan1Id 也会带上错人。配合人员同理（平台 2.6 必填
        // workMan2Id）。与手动派工 requirePlatformId(1004) 同一口径，但**跳过而不抛异常**：
        // 跑批是批量作业，为单台电梯的配置问题抛出会回滚同批其它电梯的派单。
        if (!identityOnFile(el.workerName, el.workerPlatformId)) {
            log.warn("自动派单跳过：维保人员姓名与 platform_id 未匹配上已备案员工, elevatorId={}, worker={}, platformId={}",
                    el.id, el.workerName, el.workerPlatformId);
            return null;
        }
        if (!isBlank(el.assistantName) && !identityOnFile(el.assistantName, el.assistantPlatformId)) {
            log.warn("自动派单跳过：配合人员姓名与 platform_id 未匹配上已备案员工, elevatorId={}, assistant={}, platformId={}",
                    el.id, el.assistantName, el.assistantPlatformId);
            return null;
        }
        long dueMs = TimeUtil.toMillis(last) + intervalDays(el) * 86400000L;
        if (!firstRun && now < dueMs - 86400000L) {
            return null;
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
        String todayCompact = todayStr.replace("-", "");

        WorkOrder o = new WorkOrder();
        o.id = Ids.next("wo");
        o.elevatorId = el.id;
        o.workType = checklistService.label(code);
        o.workTypeCode = code;
        o.planTime = TimeUtil.parse(planDay + " 09:00:00");
        o.status = "PENDING";
        o.workerName = el.workerName;
        o.assistantName = el.assistantName == null ? "" : el.assistantName;
        o.workerPlatformId = el.workerPlatformId;
        o.assistantPlatformId = el.assistantPlatformId;
        o.autoDispatched = true;
        // 必须传 specialType：特种设备（曳引/液压/防爆等）需按 category_scope 自动追加专项检查项，
        // 两参重载等价于 specialType=null，会漏生成这部分作业项目
        o.checklistJson = com.cqwlw.maintenance.util.JsonUtil.write(
                checklistService.buildChecklist(code, el.category, el.specialType));

        // order_no 有唯一索引，而序号按当日已有工单数推算：多台电梯同时到期时两个线程
        // 可能算出同一序号。撞唯一键时递增序号重试，避免整个派单循环被中断。
        for (int attempt = 1; attempt <= 5; attempt++) {
            o.orderNo = "WO" + todayCompact + "-" + String.format("%03d", todayOrderCount(todayCompact) + attempt);
            try {
                orderMapper.insert(o);
                break;
            } catch (DuplicateKeyException e) {
                if (attempt == 5) {
                    log.error("派单失败：工单序号连续 5 次冲突, elevatorId={}", el.id);
                    throw e;
                }
            }
        }
        Message msg = new Message();
        msg.id = Ids.next("msg");
        msg.title = "自动派单通知";
        msg.content = el.elevatorName + " " + o.workType
                + (firstRun ? "建档后首次维保安排（平台未提供上次维保时间，以本次为基准）"
                : "已到维保周期（上次维保 " + TimeUtil.format(last).substring(0, 10) + "）")
                + "，按绑定关系自动派给 " + el.workerName + "，请及时扫码签到。";
        msg.createdAt = TimeUtil.now();
        msg.readFlag = false;
        messageMapper.insert(msg);
        return o;
    }

    private long todayOrderCount(String todayCompact) {
        Long c = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .likeRight(WorkOrder::getOrderNo, "WO" + todayCompact));
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
