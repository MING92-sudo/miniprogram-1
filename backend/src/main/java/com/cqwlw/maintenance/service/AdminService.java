package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.RegUploadLog;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端聚合查询（docs/09 一期：看板 / 上报异常闭环 / 同步看板 / 统计）。
 * 工单到期口径与 WorkOrderService.homeSummary 一致；隐患分布按 problemCodesJson 聚合（docs/09 §4.1）；
 * 上报日志仅返回脱敏摘要（request_digest 落库时已脱敏，AGENTS §2.4），本服务不做明文还原。
 */
@Service
public class AdminService {

    /** 隐患码固定范围 S0—S7（docs/01：S3 重复为规范笔误，连续编码待平台确认 docs/06 #9） */
    private static final List<String> HAZARD_CODES =
            List.of("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7");

    private final WorkOrderMapper orderMapper;
    private final MaintainRecordMapper recordMapper;
    private final RegUploadLogMapper logMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final FaultMapper faultMapper;
    private final DispatchService dispatchService;
    private final PlatformTokenService tokenService;
    private final com.cqwlw.maintenance.mapper.ChecklistTemplateMapper templateMapper;

    public AdminService(WorkOrderMapper orderMapper, MaintainRecordMapper recordMapper,
                        RegUploadLogMapper logMapper, ElevatorMapper elevatorMapper,
                        EmployeeMapper employeeMapper, FaultMapper faultMapper,
                        DispatchService dispatchService, PlatformTokenService tokenService,
                        com.cqwlw.maintenance.mapper.ChecklistTemplateMapper templateMapper) {
        this.orderMapper = orderMapper;
        this.recordMapper = recordMapper;
        this.logMapper = logMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.faultMapper = faultMapper;
        this.dispatchService = dispatchService;
        this.tokenService = tokenService;
        this.templateMapper = templateMapper;
    }

    // ── 看板（GET /admin/dashboard）──

    public Map<String, Object> dashboard() {
        dispatchService.ensureDueOrders();
        String today = TimeUtil.date(TimeUtil.now());
        String soonEnd = TimeUtil.date(TimeUtil.now().plusDays(3));
        int dueToday = 0;
        int dueSoon = 0;
        int overdue = 0;
        int inProgress = 0;
        Map<String, Integer> doneByDay = new LinkedHashMap<>();
        for (WorkOrder o : orderMapper.selectList(null)) {
            String planDay = o.planTime == null ? "" : TimeUtil.date(o.planTime);
            if ("PROCESSING".equals(o.status)) {
                inProgress++;
            }
            if (!"DONE".equals(o.status)) {
                if (planDay.equals(today)) {
                    dueToday++;
                } else if (planDay.compareTo(today) > 0 && planDay.compareTo(soonEnd) <= 0) {
                    dueSoon++;
                } else if (!planDay.isEmpty() && planDay.compareTo(today) < 0) {
                    overdue++;
                }
            }
            if ("DONE".equals(o.status) && o.checkoutTime != null) {
                doneByDay.merge(TimeUtil.date(o.checkoutTime), 1, Integer::sum);
            }
        }

        long failedCount = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getReportStatus, "FAILED"));
        long reportedCount = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getReportStatus, "REPORTED"));
        long submittedCount = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getReportStatus, "SUBMITTED"));
        long unconfirmedCount = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getConfirmStatus, "PENDING"));
        int openFaults = faultMapper.selectCount(new LambdaQueryWrapper<Fault>()
                .eq(Fault::getStatus, "OPEN")).intValue();

        List<Elevator> elevators = elevatorMapper.selectList(null);
        int geoMissing = (int) elevators.stream().filter(e -> e.lng == null || e.lat == null).count();
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<>());
        int pendingSync = (int) employees.stream()
                .filter(e -> !"SYNCED".equals(e.syncStatus)).count();
        String lastSyncAt = elevators.stream()
                .map(e -> e.platformSyncedAt)
                .filter(t -> t != null)
                .max(java.time.LocalDateTime::compareTo)
                .map(TimeUtil::format).orElse("");

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String day = TimeUtil.date(TimeUtil.now().minusDays(i));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.substring(5));
            point.put("completed", doneByDay.getOrDefault(day, 0));
            trend.add(point);
        }

        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("dueToday", dueToday);
        orders.put("dueSoon", dueSoon);
        orders.put("overdue", overdue);
        orders.put("inProgress", inProgress);
        orders.put("elevatorTotal", elevators.size());

        Map<String, Object> reports = new LinkedHashMap<>();
        reports.put("failed", (int) failedCount);
        reports.put("reported", (int) reportedCount);
        reports.put("submitted", (int) submittedCount);
        reports.put("unconfirmed", (int) unconfirmedCount);
        reports.put("openFaults", openFaults);

        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("employeeTotal", employees.size());
        platform.put("employeePendingSync", pendingSync);
        platform.put("elevatorGeoMissing", geoMissing);
        platform.put("lastSyncAt", lastSyncAt);
        platform.put("platformConfigured", tokenService.configured());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders);
        out.put("reports", reports);
        out.put("platform", platform);
        out.put("trend", trend);
        out.put("warnCount", dueToday + overdue);
        return out;
    }

    // ── 维保记录管理端查询（GET /admin/records）──

    public Map<String, Object> records(Map<String, String> q) {
        LambdaQueryWrapper<MaintainRecord> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("reportStatus"))) {
            w.eq(MaintainRecord::getReportStatus, q.get("reportStatus"));
        }
        if (notBlank(q.get("confirmStatus"))) {
            w.eq(MaintainRecord::getConfirmStatus, q.get("confirmStatus"));
        }
        if (notBlank(q.get("elevatorCode"))) {
            w.eq(MaintainRecord::getElevatorCode, q.get("elevatorCode"));
        }
        if (notBlank(q.get("keyword"))) {
            String kw = q.get("keyword");
            w.and(x -> x.like(MaintainRecord::getWorkerName, kw)
                    .or().like(MaintainRecord::getElevatorName, kw)
                    .or().like(MaintainRecord::getOriginalRecordId, kw));
        }
        if (notBlank(q.get("dateFrom"))) {
            w.ge(MaintainRecord::getCreatedAt, TimeUtil.parseDate(q.get("dateFrom")).atStartOfDay());
        }
        if (notBlank(q.get("dateTo"))) {
            w.le(MaintainRecord::getCreatedAt, TimeUtil.parseDate(q.get("dateTo")).atTime(23, 59, 59));
        }
        w.orderByDesc(MaintainRecord::getCreatedAt);
        List<MaintainRecord> all = recordMapper.selectList(w);

        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());

        List<Map<String, Object>> list = new ArrayList<>();
        for (MaintainRecord r : all.subList(from, to)) {
            list.add(recordRow(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    private Map<String, Object> recordRow(MaintainRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorName", nz(r.elevatorName));
        m.put("elevatorCode", nz(r.elevatorCode));
        m.put("workType", nz(r.workType));
        m.put("workTypeCode", nz(r.workTypeCode));
        m.put("workerName", nz(r.workerName));
        m.put("assistantName", nz(r.assistantName));
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", nz(r.duration));
        m.put("problemCodes", JsonUtil.readStringList(r.problemCodesJson));
        m.put("reportStatus", nz(r.reportStatus));
        m.put("uploadStatus", UnitRecordService.uploadStatus(r.reportStatus));
        m.put("retryCount", r.retryCount == null ? 0 : r.retryCount);
        m.put("confirmStatus", nz(r.confirmStatus));
        m.put("satisfaction", r.satisfaction == null ? 0 : r.satisfaction);
        m.put("originalRecordId", nz(r.originalRecordId));
        m.put("createdAt", TimeUtil.format(r.createdAt));
        return m;
    }

    // ── 上报日志（GET /reg/upload-logs，只读脱敏）──

    public Map<String, Object> uploadLogs(Map<String, String> q) {
        LambdaQueryWrapper<RegUploadLog> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("originalRecordId"))) {
            w.eq(RegUploadLog::getOriginalRecordId, q.get("originalRecordId"));
        }
        if (notBlank(q.get("status"))) {
            w.eq(RegUploadLog::getStatus, q.get("status"));
        }
        if (notBlank(q.get("action"))) {
            w.eq(RegUploadLog::getAction, q.get("action"));
        }
        w.orderByDesc(RegUploadLog::getCreatedAt);
        List<RegUploadLog> all = logMapper.selectList(w);

        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());

        List<Map<String, Object>> list = new ArrayList<>();
        for (RegUploadLog g : all.subList(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id);
            m.put("originalRecordId", nz(g.originalRecordId));
            m.put("action", nz(g.action));
            m.put("requestDigest", nz(g.requestDigest));
            m.put("platformCode", nz(g.platformCode));
            m.put("platformMessage", nz(g.platformMessage));
            m.put("status", nz(g.status));
            m.put("createdAt", TimeUtil.format(g.createdAt));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    // ── 平台同步看板（GET /reg/sync-status，docs/04 A.3 口径）──

    public Map<String, Object> syncStatus() {
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<>());
        List<Map<String, Object>> pendingEmployees = employees.stream()
                .filter(e -> !"SYNCED".equals(e.syncStatus))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id);
                    m.put("name", nz(e.name));
                    m.put("certificate", nz(e.certificate));
                    m.put("platformId", nz(e.platformId));
                    m.put("syncStatus", nz(e.syncStatus));
                    return m;
                }).toList();

        List<Elevator> elevators = elevatorMapper.selectList(null);
        List<Map<String, Object>> geoMissing = elevators.stream()
                .filter(e -> e.lng == null || e.lat == null)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id);
                    m.put("elevatorCode", nz(e.elevatorCode));
                    m.put("elevatorName", nz(e.elevatorName));
                    return m;
                }).toList();

        String lastSyncAt = elevators.stream()
                .map(e -> e.platformSyncedAt)
                .filter(t -> t != null)
                .max(java.time.LocalDateTime::compareTo)
                .map(TimeUtil::format).orElse("");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformConfigured", tokenService.configured());
        out.put("employeeTotal", employees.size());
        out.put("employeePendingSync", pendingEmployees.size());
        out.put("employeePendingList", pendingEmployees);
        out.put("elevatorTotal", elevators.size());
        out.put("elevatorGeoMissing", geoMissing.size());
        out.put("elevatorGeoMissingList", geoMissing);
        out.put("lastSyncAt", lastSyncAt);

        // 1006 语义（docs/04 A.0）：特殊类别（消防/防爆）电梯缺少启用中的自定义模板数（TSG 第二条）
        java.util.List<String> specialTypes = java.util.List.of("消防电梯", "防爆电梯");
        java.util.Map<String, Long> customByScope = new java.util.LinkedHashMap<>();
        for (String scope : specialTypes) {
            long n = templateMapper.selectCount(new LambdaQueryWrapper<com.cqwlw.maintenance.entity.ChecklistTemplate>()
                    .eq(com.cqwlw.maintenance.entity.ChecklistTemplate::getTemplateType, "CUSTOM")
                    .eq(com.cqwlw.maintenance.entity.ChecklistTemplate::getCategoryScope, scope)
                    .eq(com.cqwlw.maintenance.entity.ChecklistTemplate::getEnabled, true));
            customByScope.put(scope, n);
        }
        java.util.List<Map<String, Object>> templateMissingList = elevators.stream()
                .filter(e -> e.specialType != null && specialTypes.contains(e.specialType))
                .filter(e -> customByScope.getOrDefault(e.specialType, 0L) == 0)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id);
                    m.put("elevatorCode", e.elevatorCode == null ? "" : e.elevatorCode);
                    m.put("elevatorName", e.elevatorName == null ? "" : e.elevatorName);
                    m.put("specialType", e.specialType);
                    return m;
                }).toList();
        out.put("templateMissing", templateMissingList.size());
        out.put("templateMissingList", templateMissingList);
        return out;
    }

    // ── 统计（GET /admin/stats，聚合在后端完成，docs/09 决策 #6）──

    public Map<String, Object> stats(String rangeDays) {
        int days = Math.max(1, Math.min(365, intOf(rangeDays, 30)));
        LocalDate startDate = TimeUtil.now().minusDays(days - 1L).toLocalDate();

        List<WorkOrder> orders = orderMapper.selectList(null);
        long doneCount = orders.stream().filter(o -> "DONE".equals(o.status)).count();
        double completionRate = orders.isEmpty()
                ? 0 : Math.round(doneCount * 1000.0 / orders.size()) / 10.0;

        List<MaintainRecord> records = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .ge(MaintainRecord::getCreatedAt, startDate.atStartOfDay()));

        Map<String, Integer> hazard = new LinkedHashMap<>();
        HAZARD_CODES.forEach(c -> hazard.put(c, 0));
        Map<String, Integer> byWorker = new LinkedHashMap<>();
        Map<String, Integer> byElevator = new LinkedHashMap<>();
        for (MaintainRecord r : records) {
            for (String code : JsonUtil.readStringList(r.problemCodesJson)) {
                if (hazard.containsKey(code)) {
                    hazard.merge(code, 1, Integer::sum);
                }
            }
            if (notBlank(r.workerName)) {
                byWorker.merge(r.workerName, 1, Integer::sum);
            }
            if (notBlank(r.elevatorCode)) {
                byElevator.merge(r.elevatorCode, 1, Integer::sum);
            }
        }

        Map<String, Object> ordersOut = new LinkedHashMap<>();
        ordersOut.put("total", orders.size());
        ordersOut.put("done", (int) doneCount);
        ordersOut.put("completionRate", completionRate);
        ordersOut.put("rangeDays", days);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", ordersOut);
        out.put("recordCount", records.size());
        out.put("hazardDist", hazard);
        out.put("byWorker", topN(byWorker, 10));
        out.put("byElevator", topN(byElevator, 10));
        return out;
    }

    private static List<Map<String, Object>> topN(Map<String, Integer> src, int n) {
        return src.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(n)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).toList();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
