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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动派单（用户确认口径 2026-09-30；2026-11-20 区域抢单池改版 D2/M4）：
 * 到期前一天把名下全部到期电梯一次性派单（同项目同日到期 → 当日一次性全部 09:00 派单）；
 * 派单不带人——工单主/配合人四个字段一律留空、落 regionId 快照（电梯所在区域），状态 PENDING=待接单，
 * 由同区域持平台ID的维保员签到认领（设计文档 §4.2）；电梯档案的绑定字段保留但派单链路不再读取（M6）。
 * 保养类型按时间自动升级（TSG 附件累加式：OY 365 / SM 180 / TM 90 / HM 15）；
 * 派单同时写入消息中心。仅由定时任务驱动（每日 09:00 + 每 13 分钟兜底），读接口一律不触发派单
 * （docs/04 A.10.9）。
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper orderMapper;
    private final MessageMapper messageMapper;
    private final EmployeeMapper employeeMapper;
    private final ChecklistService checklistService;
    private final OrderNoIssuer orderNoIssuer;
    private final TenantSnapshotService tenantSnapshots;

    public DispatchService(ElevatorMapper elevatorMapper, WorkOrderMapper orderMapper,
                           MessageMapper messageMapper, EmployeeMapper employeeMapper,
                           ChecklistService checklistService,
                           OrderNoIssuer orderNoIssuer, TenantSnapshotService tenantSnapshots) {
        this.elevatorMapper = elevatorMapper;
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.employeeMapper = employeeMapper;
        this.checklistService = checklistService;
        this.orderNoIssuer = orderNoIssuer;
        this.tenantSnapshots = tenantSnapshots;
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
     * 刻意不挂在 GET 读接口上：读请求不应产生写副作用，否则并发打开首页即可触发重复派单。
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

    /** 无需上锁的快速判断：配置缺失、停用、尚有未完成工单、未到"到期前一天" */
    private boolean maybeDue(Elevator el, long now) {
        if (el.workTypeCode == null || el.workTypeCode.isEmpty()) {
            return false;
        }
        if ("INACTIVE".equals(el.status)) {
            return false; // 停用电梯不派单（docs/09 §6.6 / V8 迁移注释）
        }
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            return false; // 一次只派一单
        }
        LocalDateTime last = lastMaintenance(el.id, null);
        if (last == null) {
            last = el.lastMaintenanceAt;
        }
        if (last == null) {
            return false;
        }
        long dueMs = TimeUtil.toMillis(last) + intervalDays(el) * 86400000L;
        return now >= dueMs - 86400000L;
    }

    private static int intervalDays(Elevator el) {
        return el.intervalDays != null ? el.intervalDays : 15;
    }

    /** 在锁内重新判定并创建工单；不满足条件返回 null */
    private WorkOrder createOrderIfDue(Elevator el, long now) {
        // 必须在锁内重查未完成工单：maybeDue 的判断发生在加锁之前，并发跑批时两个线程
        // 可能都判定为"可派"，随后各插一张工单（同一梯重复派单 → 两条 2.6 上报）
        if ("INACTIVE".equals(el.status)) {
            return null;
        }
        // 区域抢单池（D2）：无「未绑定主维保不派单」守卫——池化工单不带人，
        // 落 regionId 快照后由同区域持平台ID的维保员签到认领，天然不需要预绑定
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            return null;
        }
        LocalDateTime last = lastMaintenance(el.id, null);
        if (last == null) {
            last = el.lastMaintenanceAt;
        }
        if (last == null) {
            return null;
        }
        long dueMs = TimeUtil.toMillis(last) + intervalDays(el) * 86400000L;
        if (now < dueMs - 86400000L) {
            return null;
        }
        // 类型升级基线：某类型从未执行时以其"首次维保日期"起算（用户确认 2026-11-06），
        // 而非最近一次任意维保——否则只做过半月保的电梯永远够不到季/半年/年保升级周期。
        // 首次维保 = 最早一次 DONE 签退；无任何已完成维保时回退建档基线 lastMaintenanceAt。
        LocalDateTime first = firstMaintenance(el.id);
        if (first == null) {
            first = el.lastMaintenanceAt;
        }
        String code = "HM";
        if (elapsedDays(lastOf(el.id, "OY", first), now) >= 365) {
            code = "OY";
        } else if (elapsedDays(lastOf(el.id, "SM", first), now) >= 180) {
            code = "SM";
        } else if (elapsedDays(lastOf(el.id, "TM", first), now) >= 90) {
            code = "TM";
        }
        // 计划时间 = 到期日当天 09:00（已过期则记为今日，前端统计呈"保养超期"）
        String dueDay = TimeUtil.date(TimeUtil.fromMillis(dueMs));
        String todayStr = TimeUtil.date(TimeUtil.now());
        String planDay = dueDay.compareTo(todayStr) >= 0 ? dueDay : todayStr;
        return createOrder(el, code, TimeUtil.parse(planDay + " 09:00:00"),
                "自动派单通知", "已到维保周期（上次维保 "
                        + TimeUtil.format(last).substring(0, 10) + "）", true);
    }

    /** 生成工单 + 消息中心通知（自动/手动派单共用）；工单号统一由 OrderNoIssuer 发放。
     *  区域抢单池（D2/M4）：派单不带人——主/配合人四个字段一律留空（null），落电梯 regionId 快照；
     *  电梯事后改区域不回溯已在池工单，认领人由签到时的原子条件更新写入（设计文档 §4.2）。 */
    private WorkOrder createOrder(Elevator el, String code, LocalDateTime planTime,
                                  String msgTitle, String msgReason, boolean auto) {
        WorkOrder o = new WorkOrder();
        o.id = Ids.next("wo");
        o.elevatorId = el.id;
        o.workType = checklistService.label(code);
        o.workTypeCode = code;
        o.planTime = planTime;
        o.status = "PENDING"; // 池化语义 = 待接单
        o.regionId = el.regionId;
        o.autoDispatched = auto;
        o.checklistJson = com.cqwlw.maintenance.util.JsonUtil.write(
                checklistService.buildChecklist(code, el.category, el.specialType));
        tenantSnapshots.applyOrder(o, el);
        orderNoIssuer.insert(o);
        Message msg = new Message();
        msg.id = Ids.next("msg");
        msg.title = msgTitle;
        msg.content = el.elevatorName + " " + o.workType + msgReason
                + "，已发布到区域池，请同区域维保员到场签到接单。";
        msg.createdAt = TimeUtil.now();
        msg.readFlag = false;
        messageMapper.insert(msg);
        return o;
    }

    /** 手动派单（首保/补单）：跳过到期检查，立即发布到区域池（不带人，同区域持平台ID的维保员签到认领）；
     *  无首次维保时间的遗留电梯由管理员在此触发（用户需求）。区域抢单池（D2）后不再校验维保人员绑定。 */
    public WorkOrder dispatchNow(String elevatorId) {
        Elevator el = elevatorMapper.selectById(elevatorId);
        if (el == null) {
            throw new com.cqwlw.maintenance.common.BizException(1404, "电梯不存在");
        }
        if ("INACTIVE".equals(el.status)) {
            throw new com.cqwlw.maintenance.common.BizException(422, "该电梯已停用，不可派单（docs/09 §6.6）");
        }
        if (el.workTypeCode == null || el.workTypeCode.isEmpty()) {
            throw new com.cqwlw.maintenance.common.BizException(422, "请先设置维保周期码");
        }
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            throw new com.cqwlw.maintenance.common.BizException(422, "该电梯已有进行中的工单，不可重复派单");
        }
        return createOrder(el, el.workTypeCode, TimeUtil.parse(TimeUtil.date(TimeUtil.now()) + " 09:00:00"),
                "手动派单通知", "首保/手动派单", false);
    }

    /**
     * 到期投影（管理端维保计划页只读口径，2026-11-21）：与自动派单同一基线——
     * 最近一次 DONE 签退（缺失回退建档 lastMaintenanceAt）+ intervalDays（缺省 15），
     * 类型按累加式升级（OY 365/SM 180/TM 90/否则 HM，未执行过的类型以首保日起算）。
     * 不落库、不触发派单；无基线的电梯也返回（标注原因，便于管理端补齐）。
     * 区域抢单池（D2）：原「bound 绑定标记」替换为 regionStaffed 区域覆盖提示——
     * 该区域有无持平台ID的在职维保员（false 时前端标「区域无人」预警）；
     * 不限区域（regionId 空）时只要有任一持平台ID在职维保员即视为有人。
     */
    public List<Map<String, Object>> dueProjection() {
        long now = TimeUtil.toMillis(TimeUtil.now());
        LocalDate today = LocalDate.now();
        // 区域人员覆盖一次算出（避免逐电梯 N+1）：在职（enabled 非 false）+ 持平台ID
        Set<String> staffedRegions = new HashSet<>();
        boolean anyStaffed = false;
        List<Employee> employees = employeeMapper.selectList(null);
        if (employees != null) {
            for (Employee e : employees) {
                if (Boolean.FALSE.equals(e.enabled) || e.platformId == null || e.platformId.isBlank()) {
                    continue;
                }
                anyStaffed = true;
                if (e.regionId != null && !e.regionId.isBlank()) {
                    staffedRegions.add(e.regionId);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Elevator el : elevatorMapper.selectList(null)) {
            if ("INACTIVE".equals(el.status)) {
                continue;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("elevatorId", el.id);
            row.put("elevatorCode", el.elevatorCode);
            row.put("elevatorName", el.elevatorName);
            row.put("projectName", el.projectName);
            row.put("regionId", el.regionId);
            row.put("regionStaffed", el.regionId == null || el.regionId.isBlank()
                    ? anyStaffed : staffedRegions.contains(el.regionId));
            LocalDateTime last = lastMaintenance(el.id, null);
            if (last == null) {
                last = el.lastMaintenanceAt;
            }
            row.put("lastDoneAt", last == null ? "" : TimeUtil.format(last));
            if (last == null) {
                row.put("status", "NO_BASELINE");
                row.put("statusText", "无维保基线（需手动首保）");
                rows.add(row);
                continue;
            }
            long dueMs = TimeUtil.toMillis(last) + intervalDays(el) * 86400000L;
            LocalDate dueDay = TimeUtil.fromMillis(dueMs).toLocalDate();
            LocalDateTime first = firstMaintenance(el.id);
            if (first == null) {
                first = el.lastMaintenanceAt;
            }
            String code = "HM";
            if (elapsedDays(lastOf(el.id, "OY", first), now) >= 365) {
                code = "OY";
            } else if (elapsedDays(lastOf(el.id, "SM", first), now) >= 180) {
                code = "SM";
            } else if (elapsedDays(lastOf(el.id, "TM", first), now) >= 90) {
                code = "TM";
            }
            long days = daysBetween(today, dueDay);
            row.put("dueDate", dueDay.toString());
            row.put("workTypeCode", code);
            row.put("daysToDue", days);
            String status = days < 0 ? "OVERDUE" : days == 0 ? "TODAY" : days <= 7 ? "SOON" : "NORMAL";
            row.put("status", status);
            row.put("statusText", Map.of(
                    "OVERDUE", "超期 " + (-days) + " 天",
                    "TODAY", "今日到期",
                    "SOON", days + " 天后到期",
                    "NORMAL", "正常").get(status));
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("dueDate")), java.util.Comparator.nullsFirst(String::compareTo)));
        return rows;
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to);
    }

    /** 首保预建（安装移交/外接建档归档用）：按指定计划日 09:00 预建半月保（HM）工单并发布到区域池（不带人）。
     *  幂等守卫：该电梯已有未完成（ne DONE）工单时不重复建单，返回 null（调用方据此只回填来源单号），
     *  以免预建首保与定时派单/手动派单叠加出两张并行工单。 */
    public WorkOrder precreateFirstOrder(Elevator elevator, LocalDate planDate) {
        Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevator.id).ne(WorkOrder::getStatus, "DONE"));
        if (active != null && active > 0) {
            return null;
        }
        LocalDateTime planTime = TimeUtil.parse(planDate + " 09:00:00");
        return createOrder(elevator, "HM", planTime, "首保派单通知", "安装移交首保（半月保）", false);
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

    /** 首次维保日期 = 最早一次已完成（DONE）维保的签退时间；无任何已完成维保时返回 null（调用方回退建档基线） */
    private LocalDateTime firstMaintenance(String elevatorId) {
        LambdaQueryWrapper<WorkOrder> q = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevatorId)
                .eq(WorkOrder::getStatus, "DONE")
                .isNotNull(WorkOrder::getCheckoutTime);
        return orderMapper.selectList(q).stream()
                .map(o -> o.checkoutTime)
                .min(LocalDateTime::compareTo)
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
