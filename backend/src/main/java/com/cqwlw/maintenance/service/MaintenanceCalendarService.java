package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 电梯绑定字段池化后不作归属键。 */
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

    public Map<String, Object> calendar(String empId, LocalDate from, LocalDate to) {
        Employee emp = employeeMapper.selectById(empId);
        if (emp == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        LocalDate start = from == null ? LocalDate.now(TimeUtil.ZONE) : from;
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
        LocalDate today = LocalDate.now(TimeUtil.ZONE);
        // 纯单测未初始化 TableInfo，不能使用 Lambda 列裁剪。
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
            // 从锚点日起推进，窗口前的日期只推进状态机不出条目，保证窗口内类别与派单一致。
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

    private List<Elevator> scopedElevators(Employee emp) {
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
