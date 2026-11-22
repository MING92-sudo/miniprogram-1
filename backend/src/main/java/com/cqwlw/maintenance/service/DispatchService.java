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

    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public void scheduledDispatch() {
        int n = ensureDueOrders().size();
        log.info("定时派单完成: 新建 {} 单", n);
    }

    /** 派单判定本身幂等（该电梯已有未完成工单即跳过），故可安全重复执行。 */
    @Scheduled(cron = "0 */13 * * * ?", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public void topUpDispatch() {
        int n = ensureDueOrders().size();
        if (n > 0) {
            log.info("派单兜底补派: 新建 {} 单", n);
        }
    }

    /** 调度入口以自调用方式调用本方法，不经过 Spring 代理，故两个 @Scheduled 方法上必须各自声明 @Transactional，否则此处注解失效、行锁在自动提交下立即释放 */
    @Transactional(rollbackFor = Exception.class)
    public List<WorkOrder> ensureDueOrders() {
        List<WorkOrder> created = new ArrayList<>();
        long now = TimeUtil.toMillis(TimeUtil.now());
        for (Elevator row : elevatorMapper.selectList(null)) {
            if (!maybeDue(row, now)) {
                continue;
            }
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
        // 必须在锁内重查未完成工单：maybeDue 的判断发生在加锁之前，并发跑批时两个线程可能都判定为"可派"，随后各插一张工单（同一梯重复派单 → 两条 2.6 上报）
        if ("INACTIVE".equals(el.status)) {
            return null;
        }
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
        // 类型升级基线：某类型从未执行时以其"首次维保日期"起算，而非最近一次任意维保——否则只做过半月保的电梯永远够不到季/半年/年保升级周期。
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

    /** 电梯事后改区域不回溯已在池工单，认领人由签到时的原子条件更新写入。 */
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
        o.duoRequired = Boolean.TRUE;
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

    /** 到期日按最近一次 DONE 签退和保养周期计算。 */
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

    /** 幂等守卫：该电梯已有未完成（ne DONE）工单时不重复建单，返回 null（调用方据此只回填来源单号），以免预建首保与定时派单/手动派单叠加出两张并行工单。 */
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
