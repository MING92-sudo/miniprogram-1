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

/**
 * 维保日历（Task 11）：只读前向投影，不预生成计划、不落库（设计文档 §9.2）。
 * 锚点=该梯首保（首次 HM 半月保）计划日；此后逐日推进，
 * 按累加式规则判定当天应做的最高类别：365 天=年度 OY、180=半年 SM、90=季度 TM、15 的倍数=半月 HM。
 * 可见范围：本期=本人名下电梯（worker_platform_id）；区域/班组扩大留待 Task 8 的谓词接进来。
 */
@Service
public class MaintenanceCalendarService {

    private static final Map<String, String> WORK_TYPE_LABEL = Map.of(
            "HM", "半月维保", "TM", "季度维保", "SM", "半年维保", "OY", "年度维保");
    /** 默认投影窗口（月） */
    private static final int DEFAULT_MONTHS = 12;

    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper workOrderMapper;

    public MaintenanceCalendarService(ElevatorMapper elevatorMapper,
                                      EmployeeMapper employeeMapper,
                                      WorkOrderMapper workOrderMapper) {
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.workOrderMapper = workOrderMapper;
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
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Elevator el : scopedElevators(emp)) {
            LocalDate anchor = anchorDate(el);
            if (anchor == null) {
                continue;
            }
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                long days = ChronoUnit.DAYS.between(anchor, d);
                String type = typeAt(days);
                if (type == null) {
                    continue;
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
     * 累加式判定（设计文档 §6/§9.2）：年度覆盖当年全部项目，故取当日最高类别。
     * 天数为负（锚点在未来）返回 null。
     */
    static String typeAt(long days) {
        if (days < 0) {
            return null;
        }
        if (days == 0 || days % 365 == 0) {
            return days == 0 ? "HM" : "OY";
        }
        if (days % 180 == 0) {
            return "SM";
        }
        if (days % 90 == 0) {
            return "TM";
        }
        if (days % 15 == 0) {
            return "HM";
        }
        return null;
    }

    /** 首保锚点：最早一张半月保（HM）工单计划日 → 归档日+15 天 → 建档维保日，取首个可用 */
    LocalDate anchorDate(Elevator el) {
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
        return el.nextMaintenanceDate;
    }

    /** 本期可见范围：本人名下（worker_platform_id）；区域/班组扩大在 Task 8 接入 */
    private List<Elevator> scopedElevators(Employee emp) {
        LambdaQueryWrapper<Elevator> q = new LambdaQueryWrapper<Elevator>()
                .ne(Elevator::getStatus, "INACTIVE");
        if (emp.platformId != null && !emp.platformId.isBlank()) {
            q.eq(Elevator::getWorkerPlatformId, emp.platformId);
        } else {
            q.eq(Elevator::getWorkerName, emp.name);
        }
        List<Elevator> list = elevatorMapper.selectList(q);
        return list == null ? List.of() : list;
    }
}
