package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 维保日历（Task 11）：只读前向投影，不预生成计划、不落库（设计文档 §9.2）。
 * 锚点=该梯首保（首次 HM 半月保）计划日；此后逐日推进，
 * 按累加式规则判定当天应做的最高类别：365 天=年度 OY、180=半年 SM、90=季度 TM、15 的倍数=半月 HM。
 * 建档即排期（2026-11-20 用户口径）：锚点链追加「建档基线 lastMaintenanceAt + intervalDays」，
 * 管理端直建电梯建档时默认基线=建档时间，故建档当日起即可投影全年计划。
 * 条目带执行状态：当日有 DONE 工单=已完成；有未完成工单=已派单；计划日已过且未派单=超期；否则=计划。
 * 可见范围（2026-11-21 区域抢单池 M9 修正）：与 ElevatorService.listView 同谓词——管理角色不限，
 * 非管理角色按区域匹配键（电梯不限区域或与登录人同区域）；电梯绑定字段池化后不作归属键。
 */
@Service
public class MaintenanceCalendarService {

    private static final Map<String, String> WORK_TYPE_LABEL = Map.of(
            "HM", "半月维保", "TM", "季度维保", "SM", "半年维保", "OY", "年度维保");
    /** 条目执行状态文案（与 constants CALENDAR_STATUS 同步） */
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "PLANNED", "计划", "DISPATCHED", "已派单", "DONE", "已完成", "OVERDUE", "超期");
    /** 默认投影窗口（月） */
    private static final int DEFAULT_MONTHS = 12;

    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper workOrderMapper;
    private final EmployeeScopeService scopeService;

    public MaintenanceCalendarService(ElevatorMapper elevatorMapper,
                                      EmployeeMapper employeeMapper,
                                      WorkOrderMapper workOrderMapper,
                                      EmployeeScopeService scopeService) {
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.workOrderMapper = workOrderMapper;
        this.scopeService = scopeService;
    }

    /**
     * 投影指定窗口内的应做维保日。from/to 为空时默认「今天 → 今天+12 个月」。
     * 返回按日期升序的扁平列表，每条一台电梯一天，便于小程序按日分组渲染。
     */
    public Map<String, Object> calendar(String empId, LocalDate from, LocalDate to) {
        Employee emp = employeeMapper.selectById(empId);
        if (emp == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusMonths(DEFAULT_MONTHS) : to;
        if (end.isBefore(start)) {
            throw new BizException(422, "结束日期不能早于开始日期");
        }
        List<Elevator> elevators = scopedElevators(emp);
        // 窗口内工单一次查出，按「电梯|计划日」分组用于条目状态标注（避免逐条 N+1）
        Map<String, WorkOrder> doneByDay = new LinkedHashMap<>();
        Map<String, WorkOrder> openByDay = new LinkedHashMap<>();
        if (!elevators.isEmpty()) {
            List<WorkOrder> windowOrders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                    .in(WorkOrder::getElevatorId, elevators.stream().map(e -> e.id).toList())
                    .isNotNull(WorkOrder::getPlanTime)
                    .ge(WorkOrder::getPlanTime, start.atStartOfDay())
                    .lt(WorkOrder::getPlanTime, end.plusDays(1).atStartOfDay()));
            if (windowOrders != null) {
                for (WorkOrder o : windowOrders) {
                    // DONE 按实际签退日标注（提前/延后维保落在真实作业日）；未完成单按计划日
                    LocalDate day = "DONE".equals(o.status) && o.checkoutTime != null
                            ? o.checkoutTime.toLocalDate() : o.planTime.toLocalDate();
                    String key = o.elevatorId + "|" + day;
                    if ("DONE".equals(o.status)) {
                        doneByDay.put(key, o);
                    } else {
                        openByDay.putIfAbsent(key, o);
                    }
                }
            }
        }
        LocalDate today = LocalDate.now();
        // 每梯 DONE 历史一次批量查出（避免逐梯 N+1；不用 .select 列裁剪——Lambda 列解析
        // 依赖 TableInfo 缓存，纯单测环境未初始化会抛 lambda cache 异常，与 windowOrders 查询同风格）：
        // 类别升级的 per-type 基线 = DispatchService「lastOf(类型, 首保)」同口径——
        // 锚点（最近一次任意类型 DONE 签退日）滚动只顺延节奏，不得把 TM/SM/OY 的
        // 「上次同类」视为刚发生（旧实现 lastOY/SM/TM 恒从 0 起算，稳定维保的电梯永不投影季度以上）。
        Map<String, List<WorkOrder>> doneHistory = new LinkedHashMap<>();
        if (!elevators.isEmpty()) {
            List<WorkOrder> history = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                    .in(WorkOrder::getElevatorId, elevators.stream().map(e -> e.id).toList())
                    .eq(WorkOrder::getStatus, "DONE")
                    .isNotNull(WorkOrder::getCheckoutTime));
            if (history != null) {
                for (WorkOrder o : history) {
                    if (!"DONE".equals(o.status) || o.checkoutTime == null) {
                        continue; // mock 直通防御（生产 SQL 已过滤）
                    }
                    doneHistory.computeIfAbsent(o.elevatorId, k -> new ArrayList<>()).add(o);
                }
            }
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Elevator el : elevators) {
            LocalDate anchor = anchorDate(el);
            if (anchor == null) {
                continue;
            }
            // 类别状态机（2026-11-21 修复，与 DispatchService.createOrderIfDue 同口径）：
            // 锚点日=HM；格点（step=电梯 intervalDays）上距上次同类 ≥365/180/90 天升级为 年度/半年/季度，
            // 否则半月；年度落首个 ≥365 的格点（不再孤立在节奏格外）；从锚点日起推进，
            // 窗口前的日期只推进状态机不出条目，保证窗口内类别与派单一致。
            // per-type 基线（同日审查修复）：「上次同类」取该类型最近一次 DONE 签退，
            // 未执行过的类型回退首保日（最早 DONE 签退，DispatchService 的 first 口径）；
            // 无 DONE 历史时保持建档口径——各类别时钟自锚点起算（2026-11-21 用户确认）。
            int step = el.intervalDays != null && el.intervalDays > 0 ? el.intervalDays : 15;
            long lastOY = 0, lastSM = 0, lastTM = 0;
            List<WorkOrder> hist = doneHistory.getOrDefault(el.id, List.of());
            if (!hist.isEmpty()) {
                LocalDate firstDone = null;
                Map<String, LocalDate> lastOfType = new LinkedHashMap<>();
                for (WorkOrder o : hist) {
                    LocalDate cd = o.checkoutTime.toLocalDate();
                    if (firstDone == null || cd.isBefore(firstDone)) {
                        firstDone = cd;
                    }
                    if (o.workTypeCode != null) {
                        LocalDate cur = lastOfType.get(o.workTypeCode);
                        if (cur == null || cd.isAfter(cur)) {
                            lastOfType.put(o.workTypeCode, cd);
                        }
                    }
                }
                // 负偏移：基线日在锚点之前，days−lastT 即「距上次同类」天数（=派单 elapsedDays 口径）
                lastOY = -ChronoUnit.DAYS.between(lastOfType.getOrDefault("OY", firstDone), anchor);
                lastSM = -ChronoUnit.DAYS.between(lastOfType.getOrDefault("SM", firstDone), anchor);
                lastTM = -ChronoUnit.DAYS.between(lastOfType.getOrDefault("TM", firstDone), anchor);
            }
            for (LocalDate d = anchor; !d.isAfter(end); d = d.plusDays(1)) {
                long days = ChronoUnit.DAYS.between(anchor, d);
                String type;
                if (days == 0) {
                    type = "HM";
                } else if (days % step != 0) {
                    continue;
                } else if (days - lastOY >= 365) {
                    type = "OY";
                    lastOY = days;
                } else if (days - lastSM >= 180) {
                    type = "SM";
                    lastSM = days;
                } else if (days - lastTM >= 90) {
                    type = "TM";
                    lastTM = days;
                } else {
                    type = "HM";
                }
                if (d.isBefore(start)) {
                    continue; // 窗口前只推进类别状态机
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", d.toString());
                row.put("elevatorId", el.id);
                row.put("elevatorCode", el.elevatorCode);
                row.put("elevatorName", el.elevatorName);
                row.put("projectName", el.projectName);
                row.put("location", el.location);
                row.put("anchorDate", anchor.toString());
                row.put("workTypeCode", type);
                row.put("workType", WORK_TYPE_LABEL.get(type));
                WorkOrder ref = doneByDay.getOrDefault(el.id + "|" + d, openByDay.get(el.id + "|" + d));
                String status = doneByDay.containsKey(el.id + "|" + d) ? "DONE"
                        : ref != null ? "DISPATCHED"
                        : d.isBefore(today) ? "OVERDUE" : "PLANNED";
                row.put("status", status);
                row.put("statusText", STATUS_LABEL.get(status));
                row.put("orderId", ref == null || ref.id == null ? "" : ref.id);
                row.put("orderNo", ref == null || ref.orderNo == null ? "" : ref.orderNo);
                entries.add(row);
            }
        }
        entries.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date")))
                .thenComparing(m -> String.valueOf(m.get("elevatorName"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", start.toString());
        result.put("to", end.toString());
        result.put("entries", entries);
        return result;
    }


    /**
     * 首保锚点，取首个可用：最早一张半月保（HM）工单计划日 → 归档日+15 天 → 下次到期日
     * → 建档基线 lastMaintenanceAt + intervalDays（建档即排期：直建电梯默认基线=建档时间，首保=+15 天）
     */
    /**
     * 锚点（2026-11-21 修复，用户确认）：随实际维保滚动——有 DONE 工单时取**最近一次 DONE 签退日**，
     * 提前/延后维保后日历与派单同口径顺延（旧实现锚定「最早 HM 工单计划日」永久冻结，
     * 提前维保后投影与派单脱节）。尚无 DONE 时保持旧链：
     * 最早 HM 工单计划日（首保前投影）→ 移交归档+15 → 下次到期 → 建档基线+intervalDays。
     */
    LocalDate anchorDate(Elevator el) {
        WorkOrder lastDone = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id)
                .eq(WorkOrder::getStatus, "DONE")
                .isNotNull(WorkOrder::getCheckoutTime)
                .orderByDesc(WorkOrder::getCheckoutTime)
                .last("LIMIT 1"));
        if (lastDone != null && lastDone.checkoutTime != null) {
            return lastDone.checkoutTime.toLocalDate();
        }
        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id)
                .eq(WorkOrder::getWorkTypeCode, "HM")
                .isNotNull(WorkOrder::getPlanTime)
                .orderByAsc(WorkOrder::getPlanTime));
        if (orders != null) {
            for (WorkOrder o : orders) {
                if (o.planTime != null) {
                    return o.planTime.toLocalDate();
                }
            }
        }
        if (el.handoverAt != null) {
            return el.handoverAt.toLocalDate().plusDays(15);
        }
        if (el.nextMaintenanceDate != null) {
            return el.nextMaintenanceDate;
        }
        if (el.lastMaintenanceAt != null) {
            return el.lastMaintenanceAt.toLocalDate()
                    .plusDays(el.intervalDays != null ? el.intervalDays : 15);
        }
        return null;
    }

    /** 本期可见范围：本人名下（worker_platform_id）；区域/班组扩大在 Task 8 接入 */
    private List<Elevator> scopedElevators(Employee emp) {
        // 区域抢单池读谓词（2026-11-21 M9，与 ElevatorService.listView 同口径）：
        // 管理角色（ids==null）不限；非管理角色——电梯不限区域（regionId 空）或与登录人同区域可见。
        // 电梯绑定字段（worker*/assistant*）池化后不作归属键（旧 workerName/workerPlatformId 过滤已失效）。
        Set<String> ids = scopeService.visibleReadPlatformIds(emp);
        List<Elevator> list = elevatorMapper.selectList(new LambdaQueryWrapper<>());
        if (list == null) {
            return List.of();
        }
        return list.stream()
                // 停用电梯不可见；status 多为 NULL，SQL .ne 会误杀 NULL 行，故内存过滤（同 listView 口径）
                .filter(el -> !"INACTIVE".equals(el.status))
                .filter(el -> ids == null || el.regionId == null || el.regionId.isBlank()
                        || el.regionId.equals(emp.regionId))
                .toList();
    }
}
