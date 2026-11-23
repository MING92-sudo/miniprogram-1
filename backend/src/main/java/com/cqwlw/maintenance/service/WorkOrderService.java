package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.GeoUtil;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    private static final int DEFAULT_MIN_WORK_DURATION_MINUTES = 30;
    /** 异常项必须显式记录 S0—S7；仅无异常项时签退自动补 S0。 */
    private static final Set<String> PROBLEM_CODES = Set.of(
            "S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7");
    private static final Map<String, Integer> WORK_TYPE_INTERVAL_DAYS =
            Map.of("FM", 30, "HM", 15, "TM", 90, "SM", 180, "OY", 365);
    private static final int DYNAMIC_CODE_STEP_SECONDS = 5;
    /** 绑定工单，60 秒内可校验且一次性使用。 */
    private static final int DYNAMIC_CODE_TTL_SECONDS = 60;
    private static final SecureRandom SHARE_TOKEN_RANDOM = new SecureRandom();

    private final WorkOrderMapper orderMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final CompanyMapper companyMapper;
    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final InspectRecordMapper inspectMapper;
    private final HandoverOrderMapper handoverOrderMapper;
    private final MessageMapper messageMapper;
    private final ChecklistService checklistService;
    private final PlatformReportService reportService;
    private final EmployeeScopeService scopeService;
    private final CheckinThresholdService thresholdService;
    private final com.cqwlw.maintenance.config.AppProperties props;
    private final FileStorageService fileStorage;
    private final TransactionTemplate transactionTemplate;

    private RecordLifecycleService recordLifecycleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecordLifecycleService(RecordLifecycleService recordLifecycleService) {
        this.recordLifecycleService = recordLifecycleService;
    }

    private TenantSnapshotService tenantSnapshotService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTenantSnapshotService(TenantSnapshotService tenantSnapshotService) {
        this.tenantSnapshotService = tenantSnapshotService;
    }

    private PhoneMutexService phoneMutexService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhoneMutexService(PhoneMutexService phoneMutexService) {
        this.phoneMutexService = phoneMutexService;
    }

    /** 与其它审计点「失败吞异常」口径不同：退回池审计是清库前唯一留证，属合规关键证据——未注入时退回池 fail-closed（500），绝不静默清库丢审计。 */
    private AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    private com.cqwlw.maintenance.mapper.RegionMapper regionMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegionMapper(com.cqwlw.maintenance.mapper.RegionMapper regionMapper) {
        this.regionMapper = regionMapper;
    }

    /** 区域名称（与 AdminArchiveService.regionNameOf 同口径：空/查不到/未注入回空串） */
    private String regionNameOf(String regionId) {
        if (regionId == null || regionId.isBlank() || regionMapper == null) {
            return "";
        }
        com.cqwlw.maintenance.entity.Region r = regionMapper.selectById(regionId);
        return r == null ? "" : nz(r.name);
    }

    /** 未注入校验服务时跳过（兼容旧测试）。 */
    private void requirePhoneMutexForOrder(WorkOrder o, Elevator el) {
        if (phoneMutexService == null) {
            return;
        }
        List<Map<String, Object>> conflicts = phoneMutexService.checkWorkOrder(
                o.workerPlatformId, o.assistantPlatformId, el);
        if (!conflicts.isEmpty()) {
            throw new BizException(1002, "手机号互斥冲突：本次维保作业的五个角色手机号存在重复，请核对档案后再作业（docs/01 §3.2.4）",
                    Map.of("conflicts", conflicts));
        }
    }

    public WorkOrderService(WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                            UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                            CompanyMapper companyMapper, MaintainRecordMapper recordMapper,
                            FaultMapper faultMapper, InspectRecordMapper inspectMapper,
                            HandoverOrderMapper handoverOrderMapper, MessageMapper messageMapper,
                            ChecklistService checklistService,
                            PlatformReportService reportService,
                            EmployeeScopeService scopeService,
                            CheckinThresholdService thresholdService,
                            FileStorageService fileStorage,
                            TransactionTemplate transactionTemplate,
                            com.cqwlw.maintenance.config.AppProperties props) {
        this.orderMapper = orderMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.companyMapper = companyMapper;
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.inspectMapper = inspectMapper;
        this.handoverOrderMapper = handoverOrderMapper;
        this.messageMapper = messageMapper;
        this.checklistService = checklistService;
        this.reportService = reportService;
        this.scopeService = scopeService;
        this.thresholdService = thresholdService;
        this.fileStorage = fileStorage;
        this.transactionTemplate = transactionTemplate;
        this.props = props;
    }

    private int minWorkDurationMinutes() {
        int v = props.getWorkDurationMinutes();
        return v < 0 ? DEFAULT_MIN_WORK_DURATION_MINUTES : v;
    }

    public Map<String, Object> homeSummary(String empId) {
        String today = TimeUtil.date(TimeUtil.now());
        String soonEnd = TimeUtil.date(TimeUtil.now().plusDays(3));
        int dueToday = 0;
        int dueSoon = 0;
        int overdue = 0;
        int inProgress = 0;
        int openOrders = 0;
        for (WorkOrder o : scopeOrders(orderMapper.selectList(null), empId)) {
            String planDay = o.planTime == null ? "" : TimeUtil.date(o.planTime);
            if (!"DONE".equals(o.status)) {
                openOrders++;
            }
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
        }
        long unconfirmed = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getConfirmStatus, "PENDING"));
        // 注意：条件块必须在 SQL 里完全缺席，不能写成空的 .and(w -> {})——MyBatis-Plus 仍会拼出「AND ()」，导致 MySQL 语法错误 1064（管理角色首页 500 的根因）；
        LambdaQueryWrapper<Fault> openFaultQuery = new LambdaQueryWrapper<Fault>().eq(Fault::getStatus, "OPEN");
        Set<String> faultyCreatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
        if (faultyCreatorIds != null) {
            if (faultyCreatorIds.isEmpty()) {
                openFaultQuery.isNull(Fault::getCreatedBy);
            } else {
                openFaultQuery.and(w -> w.in(Fault::getCreatedBy, faultyCreatorIds).or().isNull(Fault::getCreatedBy));
            }
        }
        long openFaults = faultMapper.selectCount(openFaultQuery);
        int overdueInspects = 0;
        for (Elevator el : elevatorMapper.selectList(null)) {
            Long done = inspectMapper.selectCount(new LambdaQueryWrapper<InspectRecord>()
                    .eq(InspectRecord::getElevatorId, el.id));
            if ((done == null || done == 0) && el.nextCheckDate != null
                    && !el.nextCheckDate.isAfter(LocalDate.now(TimeUtil.ZONE).plusDays(30))) {
                overdueInspects++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dueToday", dueToday);
        m.put("dueSoon", dueSoon);
        m.put("overdue", overdue);
        m.put("inProgress", inProgress);
        m.put("openOrders", openOrders);
        m.put("unconfirmed", unconfirmed);
        m.put("platformTotal", elevatorMapper.selectCount(null));
        m.put("openFaults", openFaults);
        m.put("overdueInspects", overdueInspects);
        m.put("warnCount", dueToday + overdue);
        return m;
    }

    // ── 列表 ──
    public Map<String, Object> listOrders(Map<String, String> query, String empId) {
        LambdaQueryWrapper<WorkOrder> w = new LambdaQueryWrapper<>();
        Employee emp = scopeService.require(empId);
        applyOrderReadScope(w, emp, scopeService.visibleReadPlatformIds(emp));

        String due = query.get("due");
        if (due != null && !due.isEmpty()) {
            java.time.LocalDate today = TimeUtil.now().toLocalDate();
            w.isNotNull(WorkOrder::getPlanTime).ne(WorkOrder::getStatus, "DONE");
            switch (due) {
                case "today" -> w.ge(WorkOrder::getPlanTime, today.atStartOfDay())
                        .lt(WorkOrder::getPlanTime, today.plusDays(1).atStartOfDay());
                case "soon" -> w.ge(WorkOrder::getPlanTime, today.plusDays(1).atStartOfDay())
                        .lt(WorkOrder::getPlanTime, today.plusDays(4).atStartOfDay());
                case "overdue" -> w.lt(WorkOrder::getPlanTime, today.atStartOfDay());
                default -> {
                    // 旧口径：未知 due 仍要求有计划日期且未完成，不再加日期窗口。
                }
            }
        }
        String status = query.get("status");
        if (status != null && !status.isEmpty()) {
            if ("OPEN".equals(status)) {
                w.ne(WorkOrder::getStatus, "DONE");
            } else {
                w.eq(WorkOrder::getStatus, status);
            }
        }
        String keyword = query.get("keyword");
        if (keyword != null && !keyword.isEmpty()) {
            String k = escapeLikeLiteral(keyword.toLowerCase());
            List<Elevator> matches = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                    .and(x -> x.like(Elevator::getElevatorName, k).or().like(Elevator::getElevatorCode, k)
                            .or().like(Elevator::getDeviceCode, k).or().like(Elevator::getRegCode, k)
                            .or().like(Elevator::getInsideNumber, k)));
            Set<String> elevatorIds = new java.util.LinkedHashSet<>();
            for (Elevator el : matches == null ? List.<Elevator>of() : matches) {
                if (el != null && el.id != null) {
                    elevatorIds.add(el.id);
                }
            }
            w.and(x -> {
                if (!elevatorIds.isEmpty()) {
                    x.in(WorkOrder::getElevatorId, elevatorIds).or().like(WorkOrder::getOrderNo, k);
                } else {
                    x.like(WorkOrder::getOrderNo, k);
                }
            });
        }

        int page = Math.max(1, parseInt(query.get("page"), 1));
        int size = Math.max(1, parseInt(query.get("size"), 20));
        Page<WorkOrder> result = orderMapper.selectPage(new Page<>(page, size), w.orderByDesc(WorkOrder::getPlanTime));
        List<WorkOrder> current = result.getRecords() == null ? List.of() : result.getRecords();
        if (current.isEmpty()) {
            return JsonUtil.map("list", List.of(), "total", (int) result.getTotal());
        }

        Map<String, Elevator> elevatorMap = new java.util.HashMap<>();
        Set<String> elevatorIds = current.stream().map(o -> o.elevatorId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        if (!elevatorIds.isEmpty()) {
            List<Elevator> elevators = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                    .in(Elevator::getId, elevatorIds));
            for (Elevator el : elevators == null ? List.<Elevator>of() : elevators) {
                if (el != null) {
                    elevatorMap.put(el.id, el);
                }
            }
        }
        Map<String, String> regionNames = new java.util.HashMap<>();
        Set<String> regionIds = current.stream().map(o -> o.regionId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        if (regionMapper != null && !regionIds.isEmpty()) {
            List<com.cqwlw.maintenance.entity.Region> regionRows = regionMapper.selectList(
                    new LambdaQueryWrapper<com.cqwlw.maintenance.entity.Region>()
                            .in(com.cqwlw.maintenance.entity.Region::getId, regionIds));
            for (com.cqwlw.maintenance.entity.Region region : regionRows == null
                    ? List.<com.cqwlw.maintenance.entity.Region>of() : regionRows) {
                regionNames.put(region.id, region.name);
            }
        }
        List<Map<String, Object>> views = current.stream()
                .map(o -> toMap(o, false, elevatorMap.get(o.elevatorId), regionNames.getOrDefault(o.regionId, "")))
                .collect(Collectors.toList());
        return JsonUtil.map("list", views, "total", (int) result.getTotal());
    }

    private void applyOrderReadScope(LambdaQueryWrapper<WorkOrder> w, Employee emp, Set<String> ids) {
        if (ids == null) {
            return;
        }
        boolean claimed = !ids.isEmpty();
        boolean pool = emp.platformId != null && !emp.platformId.isBlank();
        if (!claimed && !pool) {
            w.apply("1 = 0");
            return;
        }
        w.and(root -> {
            if (claimed) {
                root.in(WorkOrder::getWorkerPlatformId, ids).or().in(WorkOrder::getAssistantPlatformId, ids);
            } else {
                root.apply("1 = 0");
            }
            if (pool) {
                root.or().and(poolScope -> {
                    poolScope.and(worker -> worker.isNull(WorkOrder::getWorkerPlatformId)
                            .or().eq(WorkOrder::getWorkerPlatformId, ""));
                    poolScope.and(region -> {
                        region.isNull(WorkOrder::getRegionId).or().eq(WorkOrder::getRegionId, "");
                        if (emp.regionId != null && !emp.regionId.isBlank()) {
                            region.or().eq(WorkOrder::getRegionId, emp.regionId);
                        }
                    });
                });
            }
        });
    }

    public static String escapeLikeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean contains(String s, String k) {
        return s != null && s.toLowerCase().contains(k);
    }

    public Map<String, Object> paginate(List<Map<String, Object>> list, Map<String, String> query) {
        int page = Math.max(1, parseInt(query.get("page"), 1));
        int size = Math.max(1, parseInt(query.get("size"), 20));
        int from = Math.min((page - 1) * size, list.size());
        int to = Math.min(from + size, list.size());
        return JsonUtil.map("list", list.subList(from, to), "total", list.size());
    }

    private static int parseInt(String s, int dft) {
        try {
            return s == null || s.isEmpty() ? dft : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    // ── 详情 / 扫码识单 ──
    public WorkOrder findOr404(String id) {
        WorkOrder o = orderMapper.selectById(id);
        if (o == null) {
            throw new BizException(1404, "工单不存在");
        }
        return o;
    }

    public Map<String, Object> getOrderView(String id, String empId) {
        WorkOrder o = findOr404(id);
        requireOrderReadScope(o, empId);
        return toMap(o, true);
    }

    public List<WorkOrder> scopeOrders(List<WorkOrder> list, String empId) {
        Employee emp = scopeService.require(empId);
        Set<String> ids = scopeService.visibleReadPlatformIds(emp);
        if (ids == null) {
            return list;
        }
        return list.stream().filter(o -> in(ids, o.workerPlatformId)
                || in(ids, o.assistantPlatformId)
                || poolVisible(o, emp)).collect(Collectors.toList());
    }

    public long countActionableOrders(String empId) {
        List<WorkOrder> open = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING"));
        return scopeOrders(open, empId).size();
    }

    public void requireOrderScope(WorkOrder o, String empId) {
        Employee emp = scopeService.require(empId);
        Set<String> ids = scopeService.visibleWorkerPlatformIds(emp);
        requireOrderInScope(o, emp, ids);
    }

    private void requireOrderReadScope(WorkOrder o, String empId) {
        Employee emp = scopeService.require(empId);
        requireOrderInScope(o, emp, scopeService.visibleReadPlatformIds(emp));
    }

    private static void requireOrderInScope(WorkOrder o, Employee emp, Set<String> ids) {
        if (ids == null || in(ids, o.workerPlatformId) || in(ids, o.assistantPlatformId)
                || (!ids.isEmpty() && poolVisible(o, emp))) {
            return;
        }
        throw new BizException(1403, "仅可查看或操作本人工单或本班组工单");
    }

    /** null 安全 contains：未认领先兆（workerPlatformId 为 null）不得触发不可变集合 NPE */
    private static boolean in(Set<String> ids, String platformId) {
        return platformId != null && ids.contains(platformId);
    }

    private static boolean poolVisible(WorkOrder o, Employee emp) {
        if (o.workerPlatformId != null && !o.workerPlatformId.isBlank()) {
            return false; // 已认领：维持 worker/assistant 命中判断
        }
        if (emp.platformId == null || emp.platformId.isBlank()) {
            return false;
        }
        return o.regionId == null || o.regionId.isBlank() || o.regionId.equals(emp.regionId);
    }

    public Map<String, Object> resolveByElevator(String elevatorCode, String empId) {
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, elevatorCode).last("LIMIT 1"));
        if (el == null) {
            throw new BizException(1404, "未识别的电梯二维码");
        }
        WorkOrder o = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"))
                .stream().findFirst().orElse(null);
        if (o == null) {
            throw new BizException(1404, "该电梯暂无进行中的工单");
        }
        requireOrderReadScope(o, empId); // 与其他读端点同样校验班组归属
        return toMap(o, true);
    }

    // ── 签到 ──

    /** 合规底线：定位失败如实提示，严禁伪造坐标兜底）； */
    public Map<String, Object> checkin(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        if (!"PENDING".equals(o.status) && !"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许签到");
        }
        boolean assistant = "ASSISTANT".equals(body.get("role"));
        if (hasCheckin(assistant ? o.assistantCheckinExtraJson : o.checkinExtraJson)) {
            throw new BizException(1003, (assistant ? "配合人员" : "主维保人员") + "已签到，不可重复签到");
        }
        Double lat = dbl(body.get("latitude"));
        Double lng = dbl(body.get("longitude"));
        if (lat == null || lng == null) {
            throw new BizException(422, "签到必须携带定位坐标；定位失败请重试（严禁伪造坐标）");
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90d || lat > 90d || lng < -180d || lng > 180d) {
            throw new BizException(422, "签到定位坐标非法，请重新获取真实位置");
        }
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        requirePhoneMutexForOrder(o, el);
        // 角色由服务端登录身份复核，不能信任请求体 role；未认领池单仍在围栏通过后原子认领。
        if (assistant) {
            // 身份比对必须先于动态码消费——顺序反了错误人选会吞掉正确人选的码
            requireAssignedAssistant(o, empId);
            consumeDynamicCode(o, str(body.get("dynamicCode")));
        } else if (!isBlank(o.workerPlatformId)) {
            requirePrincipal(o, empId == null ? null : scopeService.require(empId));
        }
        // 定位校验：电梯坐标缺失 → UNKNOWN，只留痕不拦截
        String geoStatus = "UNKNOWN";
        double distance = -1d;
        int threshold = thresholdService.resolve(el);
        if (el != null && el.lat != null && el.lng != null) {
            distance = GeoUtil.distanceMeters(lat, lng, el.lat.doubleValue(), el.lng.doubleValue());
            if (assistant) {
                geoStatus = "ASSISTANT_EXEMPT";
            } else {
                geoStatus = el.geoStatus == null || el.geoStatus.isEmpty() ? "PROVIDED" : el.geoStatus;
                if (distance > threshold) {
                    throw new BizException(1001, "签到位置超出允许范围：距电梯 " + round1(distance)
                            + " 米，阈值 " + threshold + " 米；请到现场后重试");
                }
            }
        }
        // 区域抢单池「签到即认领」：主维保签到未认领工单即原子认领。
        boolean claimedNow = !assistant && isBlank(o.workerPlatformId);
        if (claimedNow) {
            claimOrderAsPrincipal(o, empId);
        }
        String collectedAt = str(body.get("collectedAt"));
        // 作业时长只信服务器时钟；客户端采集时间仅留痕，不参与 checkinTime 或签退时长计算。
        LocalDateTime checkinTime = TimeUtil.now();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("latitude", body.get("latitude"));
        extra.put("longitude", body.get("longitude"));
        extra.put("locationAccuracy", body.get("locationAccuracy") == null ? 0 : body.get("locationAccuracy"));
        extra.put("clientCollectedAt", strOrEmpty(collectedAt));
        extra.put("role", assistant ? "ASSISTANT" : "PRINCIPAL");
        if (claimedNow) {
            extra.put("claimedViaPool", true);
        }
        extra.put("dynamicCode", strOrEmpty(body.get("dynamicCode")));
        extra.put("selfPhotoFileId", strOrEmpty(body.get("photoFileId")));
        extra.put("distance", distance < 0 ? "" : String.valueOf(round1(distance)));
        extra.put("threshold", distance < 0 ? "" : String.valueOf(threshold));
        extra.put("geoStatus", geoStatus);
        extra.put("checkedAt", TimeUtil.format(checkinTime));
        String checkinJson = JsonUtil.write(extra);
        LambdaUpdateWrapper<WorkOrder> checkinUpdate = new LambdaUpdateWrapper<>();
        checkinUpdate.eq(WorkOrder::getId, o.id)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .set(WorkOrder::getStatus, "PROCESSING");
        if (assistant) {
            checkinUpdate.and(w -> w.isNull(WorkOrder::getAssistantCheckinExtraJson)
                            .or().eq(WorkOrder::getAssistantCheckinExtraJson, ""))
                    .set(WorkOrder::getAssistantCheckinExtraJson, checkinJson);
            matchAssistant(checkinUpdate, o.assistantPlatformId);
        } else {
            checkinUpdate.and(w -> w.isNull(WorkOrder::getCheckinExtraJson)
                            .or().eq(WorkOrder::getCheckinExtraJson, ""))
                    .eq(WorkOrder::getWorkerPlatformId, o.workerPlatformId)
                    .set(WorkOrder::getCheckinExtraJson, checkinJson)
                    .set(WorkOrder::getCheckinTime, checkinTime);
        }
        if (orderMapper.update(null, checkinUpdate) != 1) {
            throw new BizException(1003, assistant ? "配合人员已变更或已签到，请刷新后重试"
                    : "主维保签到状态已变更，请刷新后重试");
        }
        if (assistant) {
            o.assistantCheckinExtraJson = checkinJson;
        } else {
            o.checkinExtraJson = checkinJson;
            o.checkinTime = checkinTime;
        }
        o.status = "PROCESSING";
        boolean geoBackfilled = false;
        if (!assistant && el != null && (el.lng == null || el.lat == null)) {
            java.math.BigDecimal backfillLng = java.math.BigDecimal.valueOf(lng);
            java.math.BigDecimal backfillLat = java.math.BigDecimal.valueOf(lat);
            int updated = elevatorMapper.backfillGeoIfMissing(el.id, backfillLng, backfillLat);
            if (updated == 1) {
                el.lng = backfillLng;
                el.lat = backfillLat;
                el.geoStatus = "SELF_COLLECTED";
                geoBackfilled = true;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkinId", Ids.next("chk"));
        out.put("role", assistant ? "ASSISTANT" : "PRINCIPAL");
        // geoStatus 表示本次校验使用的电梯坐标来源：UNKNOWN=未登记（本次未做超阈校验，只留痕）
        out.put("distance", distance < 0 ? null : round1(distance));
        out.put("threshold", distance < 0 ? null : threshold);
        out.put("passed", true);
        out.put("geoStatus", geoStatus);
        out.put("geoBackfilled", geoBackfilled);
        return out;
    }

    private void claimOrderAsPrincipal(WorkOrder o, String empId) {
        Employee login = scopeService.require(empId);
        if (login.platformId == null || login.platformId.isBlank()) {
            throw new BizException(422, "请先在管理端完成平台人员登记");
        }
        int updated = orderMapper.claimOrder(o.id, login.name, login.platformId);
        if (updated == 0) {
            WorkOrder fresh = orderMapper.selectById(o.id);
            String claimer = fresh != null && !isBlank(fresh.workerName) ? fresh.workerName : "其他维保人员";
            throw new BizException(1003, "该工单已被 " + claimer + " 认领");
        }
        o.workerName = login.name;
        o.workerPlatformId = login.platformId;
    }


    private void requireAssignedAssistant(WorkOrder o, String empId) {
        if (isBlank(o.assistantPlatformId)) {
            boolean pooledOrder = Boolean.TRUE.equals(o.duoRequired) || !isBlank(o.regionId) || claimedViaPool(o.checkinExtraJson);
            if (pooledOrder) {
                throw new BizException(1003, "主维保尚未指定配合人，请由主维保指定后再签到");
            }
            return;
        }
        Employee login = scopeService.require(empId);
        if (isBlank(login.platformId) || !login.platformId.equals(o.assistantPlatformId)) {
            throw new BizException(1003, "你未被指定为配合人（本工单配合人已由主维保指定）");
        }
    }

    /** 仅主维保本人可调用（1403）。 */
    public Map<String, Object> partnerCandidates(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        requirePrincipal(o, scopeService.require(empId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Employee e : employeeMapper.selectList(null)) {
            if (!partnerEligible(o, e)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("platformId", e.platformId);
            m.put("phone", e.phone);
            list.add(m);
        }
        return JsonUtil.map("list", list);
    }

    public Map<String, Object> assignPartner(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        requirePrincipal(o, scopeService.require(empId));
        if (!"PENDING".equals(o.status) && !"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许指定配合人");
        }
        if (hasCheckin(o.assistantCheckinExtraJson)) {
            throw new BizException(1003, "配合人员已签到，不可改选");
        }
        String employeeId = str(body.get("employeeId"));
        Employee cand = isBlank(employeeId) ? null : employeeMapper.selectById(employeeId);
        if (cand == null || !partnerEligible(o, cand)) {
            throw new BizException(422, "该员工不在配合人候选范围（需与本工单同区域且已完成平台人员登记）");
        }
        LambdaUpdateWrapper<WorkOrder> assignment = new LambdaUpdateWrapper<>();
        assignment.eq(WorkOrder::getId, o.id)
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING")
                .and(w -> w.isNull(WorkOrder::getAssistantCheckinExtraJson)
                        .or().eq(WorkOrder::getAssistantCheckinExtraJson, ""))
                .set(WorkOrder::getAssistantName, cand.name)
                .set(WorkOrder::getAssistantPlatformId, cand.platformId);
        matchAssistant(assignment, o.assistantPlatformId);
        transactionTemplate.executeWithoutResult(tx -> {
            int updated = orderMapper.update(null, assignment);
            if (updated != 1) {
                throw new BizException(1003, "配合人员已变更或已签到，请刷新后重新指定");
            }
            orderMapper.clearAssistantSignature(o.id);
        });
        o.assistantName = cand.name;
        o.assistantPlatformId = cand.platformId;
        o.assistantSignatureFileId = null;
        o.assistantSignatureAt = null;
        return JsonUtil.map("ok", true, "assistantName", nz(cand.name), "assistantPlatformId", cand.platformId);
    }

    /** 仅主维保本人：登录人 platformId == 工单 workerPlatformId（认领后 worker* 指向认领人） */
    private void requirePrincipal(WorkOrder o, Employee login) {
        if (login == null || isBlank(login.platformId) || !login.platformId.equals(o.workerPlatformId)) {
            throw new BizException(1403, "仅主维保本人可执行此操作");
        }
    }

    private static void matchAssistant(LambdaUpdateWrapper<WorkOrder> update, String expectedPlatformId) {
        if (isBlank(expectedPlatformId)) {
            update.and(w -> w.isNull(WorkOrder::getAssistantPlatformId)
                    .or().eq(WorkOrder::getAssistantPlatformId, ""));
        } else {
            update.eq(WorkOrder::getAssistantPlatformId, expectedPlatformId);
        }
    }

    private static boolean partnerEligible(WorkOrder o, Employee e) {
        if (e == null || isBlank(e.platformId)) {
            return false;
        }
        if (Boolean.FALSE.equals(e.enabled)) {
            return false;
        }
        if (e.platformId.equals(o.workerPlatformId)) {
            return false;
        }
        return isBlank(o.regionId) || o.regionId.equals(e.regionId);
    }


    /** ②未认领 PENDING → 幂等 ok，不改状态、不写审计、不动库； */
    public Map<String, Object> releaseToPool(String orderId, String actorId) {
        WorkOrder o = findOr404(orderId);
        if ("DONE".equals(o.status)) {
            throw new BizException(422, "已完成工单不可退回池（维保记录已生成并上报监管平台）");
        }
        boolean claimed = !isBlank(o.workerName) || !isBlank(o.workerPlatformId)
                || !isBlank(o.assistantPlatformId) || hasCheckin(o.checkinExtraJson)
                || hasCheckin(o.assistantCheckinExtraJson) || !"PENDING".equals(o.status);
        if (!claimed) {
            return JsonUtil.map("ok", true, "released", false, "status", o.status, "orderId", o.id);
        }
        if (auditLogService == null) {
            throw new BizException(500, "审计服务不可用，禁止退回池操作");
        }
        SysAuditLog audit = new SysAuditLog();
        audit.actorId = actorId;
        audit.actorRole = "EMPLOYEE";
        audit.action = "order:release-to-pool";
        audit.target = o.id;
        audit.beforeSnap = JsonUtil.write(releaseSnapshot(o));
        audit.reason = "管理端退回池：工单清空认领后重新进入区域池待认领";
        audit.result = "SUCCESS";
        transactionTemplate.executeWithoutResult(tx -> {
            // 审计先行 + 清库同一事务：append 抛异常 → 整体回滚，清库不落
            auditLogService.append(audit);
            int updated = orderMapper.update(null, releasePoolWrapper(orderId));
            if (updated == 0) {
                // 绝不能把 DONE 覆写回 PENDING，避免重复认领和重复上报。
                WorkOrder fresh = orderMapper.selectById(orderId);
                if (fresh == null) {
                    throw new BizException(1404, "工单不存在");
                }
                throw new BizException(422, "该工单已签退完成，不可退回池（请刷新工单状态）");
            }
        });
        return JsonUtil.map("ok", true, "released", true, "status", "PENDING", "orderId", o.id);
    }

    /** 退回前快照（审计 beforeSnap）：原认领人/原配合人/签到留痕 JSON 原文/状态/regionId 快照 */
    private static Map<String, Object> releaseSnapshot(WorkOrder o) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("orderId", o.id);
        snap.put("orderNo", nz(o.orderNo));
        snap.put("status", nz(o.status));
        snap.put("workerName", nz(o.workerName));
        snap.put("workerPlatformId", nz(o.workerPlatformId));
        snap.put("assistantName", nz(o.assistantName));
        snap.put("assistantPlatformId", nz(o.assistantPlatformId));
        snap.put("checkinTime", TimeUtil.format(o.checkinTime));
        snap.put("checkinExtraJson", nz(o.checkinExtraJson));
        snap.put("assistantCheckinExtraJson", nz(o.assistantCheckinExtraJson));
        snap.put("regionId", nz(o.regionId));
        return snap;
    }

    /** 清库条件更新（updateById 默认跳过 null 字段，清库必须显式 SET NULL，同 AdminArchiveService 电梯 clearRegion 口径）； */
    private static LambdaUpdateWrapper<WorkOrder> releasePoolWrapper(String orderId) {
        return new LambdaUpdateWrapper<WorkOrder>()
                .eq(WorkOrder::getId, orderId)
                .ne(WorkOrder::getStatus, "DONE")
                .set(WorkOrder::getWorkerName, null)
                .set(WorkOrder::getWorkerPlatformId, null)
                .set(WorkOrder::getAssistantName, null)
                .set(WorkOrder::getAssistantPlatformId, null)
                .set(WorkOrder::getCheckinExtraJson, null)
                .set(WorkOrder::getAssistantCheckinExtraJson, null)
                .set(WorkOrder::getCheckinTime, null)
                .set(WorkOrder::getWorkerSignatureFileId, null)
                .set(WorkOrder::getWorkerSignatureAt, null)
                .set(WorkOrder::getAssistantSignatureFileId, null)
                .set(WorkOrder::getAssistantSignatureAt, null)
                .set(WorkOrder::getDynamicCode, null)
                .set(WorkOrder::getDynamicCodeExpiresAt, null)
                .set(WorkOrder::getStatus, "PENDING");
    }


    /** 主维保生成/刷新动态码。 */
    public Map<String, Object> issueDynamicCode(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        requirePrincipal(o, scopeService.require(empId));
        if (!"PENDING".equals(o.status) && !"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许生成动态码");
        }
        long step = currentStep();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", dynamicCode(o.id, step));
        out.put("stepSeconds", DYNAMIC_CODE_STEP_SECONDS);
        out.put("ttlSeconds", DYNAMIC_CODE_TTL_SECONDS);
        out.put("expiresAt", TimeUtil.format(TimeUtil.fromMillis(
                (step + 1 + DYNAMIC_CODE_TTL_SECONDS / DYNAMIC_CODE_STEP_SECONDS)
                        * DYNAMIC_CODE_STEP_SECONDS * 1000L)));
        return out;
    }

    /** 配合人员到场校验（不消费动态码；消费发生在配合人员签到成功时） */
    public Map<String, Object> verifyDynamicCode(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        requireAssignedAssistant(o, empId);
        requireDynamicCode(o, str(body.get("code")));
        return JsonUtil.map("ok", true);
    }

    private void requireDynamicCode(WorkOrder o, String code) {
        if (code == null || code.isEmpty()) {
            throw new BizException(422, "配合人员签到必须携带双人动态码");
        }
        if (code.equals(o.dynamicCode)) {
            throw new BizException(1003, "动态码已使用，请主维保人员刷新后重新取码");
        }
        long step = currentStep();
        for (long s = step; s > step - DYNAMIC_CODE_TTL_SECONDS / DYNAMIC_CODE_STEP_SECONDS; s--) {
            if (dynamicCode(o.id, s).equals(code)) {
                return;
            }
        }
        throw new BizException(1003, "动态码不正确或已过期（有效期 " + DYNAMIC_CODE_TTL_SECONDS
                + " 秒），请主维保人员刷新后重试");
    }

    /** 先校验动态码，再以原子条件更新保证一次性消费。 */
    private void consumeDynamicCode(WorkOrder o, String code) {
        requireDynamicCode(o, code);
        LocalDateTime expiresAt = TimeUtil.now().plusSeconds(DYNAMIC_CODE_TTL_SECONDS);
        int updated = orderMapper.consumeDynamicCodeIfUnused(o.id, code, expiresAt);
        if (updated == 0) {
            throw new BizException(1003, "动态码已使用，请主维保人员刷新后重新取码");
        }
        o.dynamicCode = code;
        o.dynamicCodeExpiresAt = expiresAt;
    }

    long currentStep() {
        return System.currentTimeMillis() / 1000L / DYNAMIC_CODE_STEP_SECONDS;
    }

    /** 6 位数字码 = HMAC-SHA256(app.jwt-secret, 工单ID|步长) 截断取模（包内可见供单测校验时效窗口） */
    String dynamicCode(String orderId, long step) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    props.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((orderId + "|" + step).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int n = (((sig[0] & 0xff) << 16) | ((sig[1] & 0xff) << 8) | (sig[2] & 0xff)) % 1000000;
            return String.format("%06d", n);
        } catch (java.security.GeneralSecurityException e) {
            throw new BizException(500, "动态码生成失败，请重试");
        }
    }

    private static boolean hasCheckin(String json) {
        return json != null && !json.isEmpty();
    }

    private static boolean claimedViaPool(String checkinExtraJson) {
        if (checkinExtraJson == null || checkinExtraJson.isBlank()) {
            return false;
        }
        Map<String, Object> extra = JsonUtil.readMap(checkinExtraJson);
        return extra != null && Boolean.TRUE.equals(extra.get("claimedViaPool"));
    }

    private static Double dbl(Object v) {
        if (v == null) {
            return null;
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ── 检查项 ──
    public List<Map<String, Object>> items(WorkOrder o) {
        if (o.checklistJson == null || o.checklistJson.isEmpty()) {
            Elevator el = elevatorMapper.selectById(o.elevatorId);
            String category = el == null ? "" : el.category;
            String specialType = el == null || el.specialType == null ? "" : el.specialType;
            o.checklistJson = JsonUtil.write(checklistService.buildChecklist(o.workTypeCode, category, specialType));
            persistChecklist(o);
        }
        List<Map<String, Object>> items = JsonUtil.readList(o.checklistJson);
        // 历史/演示数据的本地临时路径（wxfile://、http://tmp/）不入回显，否则照片只在拍照设备可见、其他设备/管理端乱图
        for (Map<String, Object> item : items) {
            List<String> urls = asList(item.get("photoFileIds")).stream()
                    .map(id -> fileStorage.urlOf(String.valueOf(id)))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (urls.isEmpty()) {
                urls = asList(item.get("photos")).stream()
                        .map(String::valueOf)
                        .map(fileStorage::resolveStoredUrl)
                        .filter(WorkOrderService::isHttpUrl)
                        .collect(Collectors.toList());
            }
            item.put("photos", urls);
        }
        return items;
    }

    private void persistChecklist(WorkOrder o) {
        LambdaUpdateWrapper<WorkOrder> update = new LambdaUpdateWrapper<>();
        update.eq(WorkOrder::getId, o.id).set(WorkOrder::getChecklistJson, o.checklistJson);
        orderMapper.update(null, update);
    }

    public Map<String, Object> getChecklist(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderReadScope(o, empId);
        // 作业页头部上下文：类别码/维保类别/电梯名/项目名称（小区），作业清单页重设计用
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        return JsonUtil.map(
                "checklistId", o.id,
                "workTypeCode", nz(o.workTypeCode),
                "workType", nz(o.workType),
                "elevatorName", el == null ? "" : nz(el.elevatorName),
                "projectName", el == null ? "" : nz(el.projectName),
                "items", items(o));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitItem(String orderId, String itemId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        String result = str(body.get("result"));
        if (!"NORMAL".equals(result) && !"ABNORMAL".equals(result) && !"NA".equals(result)) {
            throw new BizException(422, "检查结果非法：须为 NORMAL、ABNORMAL 或 NA");
        }
        List<Map<String, Object>> items = items(o);
        Map<String, Object> item = items.stream()
                .filter(i -> itemId.equals(i.get("id"))).findFirst()
                .orElseThrow(() -> new BizException(1404, "检查项不存在"));
        String name = String.valueOf(item.get("name"));
        if ("NA".equals(result) && isBlank(str(body.get("skipReason")))) {
            throw new BizException(422, "「" + name + "」标记不适用时必须填写跳过原因（TSG 注 A-1）");
        }
        boolean hasPhoto = asList(body.get("photoFileIds")).size() > 0 || asList(body.get("photoUrls")).size() > 0;
        if ("ABNORMAL".equals(result) && isBlank(str(body.get("abnormalDesc")))) {
            throw new BizException(422, "「" + name + "」为异常时必须填写异常描述");
        }
        if ("ABNORMAL".equals(result) && !hasPhoto) {
            throw new BizException(422, "「" + name + "」为异常时必须至少附 1 张照片");
        }
        if ("ABNORMAL".equals(result)) {
            String pc = str(body.get("problemCode"));
            if (isBlank(pc)) {
                throw new BizException(422, "「" + name + "」为异常时必须记录隐患判定（S0—S7），不得留空");
            }
            if (!PROBLEM_CODES.contains(pc.trim())) {
                throw new BizException(422, "「" + name + "」隐患码非法：" + pc + "（取值范围 S0—S7）");
            }
        }
        boolean isKey = Boolean.TRUE.equals(item.get("isKey"));
        boolean photoRequired = Boolean.TRUE.equals(item.get("photoRequired"));
        if (isKey && photoRequired && !"NA".equals(result) && !hasPhoto) {
            throw new BizException(422, "关键项「" + name + "」为试验/测试/校验/检测类，必须至少附 1 张照片留证（TSG 注A-2）");
        }
        item.put("result", result);
        item.put("value", parseNumeric(body.get("value"))); // M14：数字/数字字符串放行，非法类型 422
        item.put("valueText", strOrEmpty(body.get("valueText")));
        item.put("abnormalDesc", strOrEmpty(body.get("abnormalDesc")));
        item.put("skipReason", strOrEmpty(body.get("skipReason")));
        item.put("problemCode", strOrEmpty(body.get("problemCode")));
        // 照片落档以 photoFileIds 解析出的可访问 URL 为准，不落本地临时路径（wxfile://、http://tmp/），否则照片只在拍照设备可见、其他设备/管理端乱图
        List<String> photoUrls = asList(body.get("photoFileIds")).stream()
                .map(id -> fileStorage.urlOf(String.valueOf(id)))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        item.put("photos", photoUrls);
        item.put("photoFileIds", body.get("photoFileIds") == null ? new ArrayList<>() : body.get("photoFileIds"));
        String recordedAt = str(body.get("recordedAt"));
        item.put("recordedAt", recordedAt == null || recordedAt.isEmpty()
                ? TimeUtil.format(TimeUtil.now()) : recordedAt);
        o.checklistJson = JsonUtil.write(items);
        persistChecklist(o);
        return JsonUtil.map("ok", true, "itemId", itemId);
    }

    public Map<String, Object> runThisTime(String orderId, String itemId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        List<Map<String, Object>> items = items(o);
        Map<String, Object> item = items.stream()
                .filter(i -> itemId.equals(i.get("id"))).findFirst()
                .orElseThrow(() -> new BizException(1404, "检查项不存在"));
        item.put("notInThisRun", false);
        o.checklistJson = JsonUtil.write(items);
        persistChecklist(o);
        return JsonUtil.map("ok", true);
    }

    // ── 签字 ──

    /** 普通作业人员仅可签本人对应角色；LEADER 无代签特权（{@link #requireSignRole}）； */
    public Map<String, Object> signOrder(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许签字（请先完成签到）");
        }
        String role = str(body.get("role"));
        boolean assistant = "ASSISTANT".equals(role);
        if (!assistant && !"PRINCIPAL".equals(role)) {
            throw new BizException(422, "签字角色非法：须为 PRINCIPAL 或 ASSISTANT");
        }
        requireSignRole(o, role, empId);
        boolean checkedIn = assistant ? hasCheckin(o.assistantCheckinExtraJson) : hasCheckin(o.checkinExtraJson);
        if (!checkedIn) {
            throw new BizException(422, assistant ? "配合人员未签到，不能签字" : "主维保未签到，不能签字");
        }
        String fileId = str(body.get("signatureFileId")).trim();
        if (fileId.isEmpty()) {
            throw new BizException(422, "签字图未上传，请重试");
        }
        LocalDateTime now = TimeUtil.now();
        LambdaUpdateWrapper<WorkOrder> signatureUpdate = new LambdaUpdateWrapper<>();
        signatureUpdate.eq(WorkOrder::getId, o.id).eq(WorkOrder::getStatus, "PROCESSING");
        if (assistant) {
            signatureUpdate.and(w -> w.isNotNull(WorkOrder::getAssistantCheckinExtraJson)
                            .ne(WorkOrder::getAssistantCheckinExtraJson, ""))
                    .set(WorkOrder::getAssistantSignatureFileId, fileId)
                    .set(WorkOrder::getAssistantSignatureAt, now);
            matchAssistant(signatureUpdate, o.assistantPlatformId);
        } else {
            signatureUpdate.and(w -> w.isNotNull(WorkOrder::getCheckinExtraJson)
                            .ne(WorkOrder::getCheckinExtraJson, ""))
                    .eq(WorkOrder::getWorkerPlatformId, o.workerPlatformId)
                    .set(WorkOrder::getWorkerSignatureFileId, fileId)
                    .set(WorkOrder::getWorkerSignatureAt, now);
        }
        if (orderMapper.update(null, signatureUpdate) != 1) {
            throw new BizException(1003, assistant ? "配合人员已变更，请刷新后重新签字"
                    : "主维保签字状态已变更，请刷新后重试");
        }
        if (assistant) {
            o.assistantSignatureFileId = fileId;
            o.assistantSignatureAt = now;
        } else {
            o.workerSignatureFileId = fileId;
            o.workerSignatureAt = now;
        }
        return JsonUtil.map("ok", true, "role", assistant ? "ASSISTANT" : "PRINCIPAL");
    }

    /** 签字身份校验（严格各签各的，防代签）：普通作业人员仅可签本人对应角色； */
    private void requireSignRole(WorkOrder o, String role, String empId) {
        Employee login = scopeService.require(empId);
        String r = login.role == null ? "" : login.role;
        if ("ADMIN".equals(r) || "SYS_ADMIN".equals(r)) {
            return;
        }
        String pid = login.platformId;
        if ("ASSISTANT".equals(role)) {
            if (pid == null || pid.isBlank() || !pid.equals(o.assistantPlatformId)) {
                throw new BizException(1403, "仅配合人员本人可签配合人员签字");
            }
        } else {
            if (pid == null || pid.isBlank() || !pid.equals(o.workerPlatformId)) {
                throw new BizException(1403, "仅主维保本人可签主维保签字");
            }
        }
    }

    // ── 签退 ──
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkout(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        // 仅主维保可签退：普通作业人员只有主维保本人可签退；
        Employee login = scopeService.require(empId);
        String loginRole = login.role == null ? "" : login.role;
        boolean privileged = "ADMIN".equals(loginRole) || "SYS_ADMIN".equals(loginRole);
        if (!privileged) {
            String pid = login.platformId;
            if (pid == null || pid.isBlank() || !pid.equals(o.workerPlatformId)) {
                throw new BizException(1403, "仅主维保可签退");
            }
        }
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "请先完成签到");
        }
        if (!hasCheckin(o.checkinExtraJson) || o.checkinTime == null) {
            throw new BizException(422, "主维保未签到，无法签退");
        }
        // 门禁③（双签）在下方签名校验处恒触发。
        boolean pooledOrder = Boolean.TRUE.equals(o.duoRequired) || (o.regionId != null && !o.regionId.isBlank())
                || claimedViaPool(o.checkinExtraJson);
        if (pooledOrder) {
            if (o.assistantPlatformId == null || o.assistantPlatformId.isBlank()) {
                throw new BizException(422, "请选择配合人员（双人作业，未选配合人不得签退）");
            }
            if (!hasCheckin(o.assistantCheckinExtraJson)) {
                throw new BizException(422, "配合人员未签到，无法签退（双人作业须双人签到留痕）");
            }
        }
        List<Map<String, Object>> items = items(o);
        List<Map<String, Object>> mustRun = items.stream()
                .filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun"))).collect(Collectors.toList());
        long unfinished = mustRun.stream().filter(i -> i.get("result") == null
                || String.valueOf(i.get("result")).isEmpty()).count();
        if (unfinished > 0) {
            throw new BizException(1003, "还有 " + unfinished + " 项检查未填写");
        }
        List<Map<String, Object>> keyNoPhoto = mustRun.stream().filter(i ->
                Boolean.TRUE.equals(i.get("isKey")) && Boolean.TRUE.equals(i.get("photoRequired"))
                        && i.get("result") != null && !"NA".equals(String.valueOf(i.get("result")))
                        && asList(i.get("photoFileIds")).isEmpty() && asList(i.get("photos")).isEmpty()
        ).collect(Collectors.toList());
        if (!keyNoPhoto.isEmpty()) {
            throw new BizException(422, "关键项「" + keyNoPhoto.get(0).get("name")
                    + "」须至少附 1 张照片留证（TSG 注A-2），无法签退");
        }
        LocalDateTime checkoutTime = TimeUtil.now();
        int minMinutes = minWorkDurationMinutes();
        long minutes = o.checkinTime == null ? minMinutes
                : ChronoUnit.MINUTES.between(o.checkinTime, checkoutTime);
        if (minutes < minMinutes) {
            throw new BizException(422, "作业时长不足 " + minMinutes + " 分钟（当前 " + minutes
                    + " 分钟），请继续作业后再签退");
        }

        String workerSignature = resolveSignatureUrl(
                nz(o.workerSignatureFileId).isEmpty() ? body.get("signatureFileId") : o.workerSignatureFileId,
                body.get("signatureUrl"));
        String assistantSignature = resolveSignatureUrl(
                nz(o.assistantSignatureFileId).isEmpty() ? body.get("assistantSignatureFileId") : o.assistantSignatureFileId,
                body.get("assistantSignatureUrl"));
        boolean hasAssistant = o.assistantName != null && !o.assistantName.isBlank()
                && !"无".equals(o.assistantName.trim());
        if (workerSignature.isEmpty()) {
            throw new BizException(422, "请完成主维保人员签名");
        }
        if ((pooledOrder || hasAssistant) && assistantSignature.isEmpty()) {
            throw new BizException(422, "请完成配合人员签名（双人签字）");
        }

        // 设备信息档案门禁：电梯编码/设备代码/注册代码/单位内编号/出厂编号为上传维保记录（平台 2.6）的必传项，缺任一项不得生成记录、不得上报
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        if (el == null) {
            throw new BizException(1404, "电梯档案不存在，无法上传维保记录");
        }
        DeviceProfileValidator.requireForRecord(el);
        requirePhoneMutexForOrder(o, el);

        String expectedOriginalRecordId = isBlank(o.originalRecordId) ? null : o.originalRecordId;
        boolean rework = expectedOriginalRecordId != null;
        MaintainRecord previous = null;
        if (rework) {
            previous = recordMapper.selectOne(new LambdaQueryWrapper<MaintainRecord>()
                    .eq(MaintainRecord::getWorkOrderId, o.id)
                    .orderByDesc(MaintainRecord::getVersion)
                    .orderByDesc(MaintainRecord::getCreatedAt)
                    .last("LIMIT 1"));
            if (previous == null) {
                previous = recordMapper.selectOne(new LambdaQueryWrapper<MaintainRecord>()
                        .eq(MaintainRecord::getOriginalRecordId, expectedOriginalRecordId)
                        .orderByDesc(MaintainRecord::getVersion)
                        .orderByDesc(MaintainRecord::getCreatedAt)
                        .last("LIMIT 1"));
            }
            if (previous == null) {
                throw new BizException(1404, "原维保记录不存在，无法生成本地更正版本");
            }
        }
        o.status = "DONE";
        o.checkoutTime = checkoutTime;
        o.originalRecordId = rework ? expectedOriginalRecordId : Ids.nextRecordId();
        o.reportStatus = rework ? "DRAFT" : "SUBMITTED";
        o.duration = TimeUtil.formatDuration(TimeUtil.toMillis(checkoutTime) - TimeUtil.toMillis(o.checkinTime));

        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        List<Map<String, Object>> frozen = items.stream().map(i -> new LinkedHashMap<>(i)).collect(Collectors.toList());
        List<String> photos = frozen.stream()
                .flatMap(i -> {
                    List<String> urls = asList(i.get("photoFileIds")).stream()
                            .map(id -> fileStorage.urlOf(String.valueOf(id)))
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    if (urls.isEmpty()) {
                        urls = asList(i.get("photos")).stream()
                                .map(String::valueOf)
                                .filter(WorkOrderService::isHttpUrl)
                                .collect(Collectors.toList());
                    }
                    return urls.stream();
                })
                .collect(Collectors.toList());
        List<Map<String, Object>> abnormalItems = frozen.stream()
                .filter(i -> "ABNORMAL".equals(String.valueOf(i.get("result"))))
                .collect(Collectors.toList());
        // 兜底防御：存在异常项却无隐患码的检查项（历史数据/绕过 submitItem 的写入）不得静默报 S0
        List<Map<String, Object>> abnormalUncoded = abnormalItems.stream()
                .filter(i -> isBlank(str(i.get("problemCode"))))
                .collect(Collectors.toList());
        if (!abnormalUncoded.isEmpty()) {
            throw new BizException(1003, "检查项「" + abnormalUncoded.get(0).get("name")
                    + "」为异常但未记录隐患判定，无法签退");
        }
        List<String> problemCodes = abnormalItems.stream()
                .map(i -> str(i.get("problemCode")).trim())
                .collect(Collectors.toList());
        List<String> invalidCodes = problemCodes.stream()
                .filter(pc -> !PROBLEM_CODES.contains(pc))
                .collect(Collectors.toList());
        if (!invalidCodes.isEmpty()) {
            throw new BizException(1003, "检查项隐患码非法：" + invalidCodes.get(0)
                    + "（取值范围 S0—S7），无法签退");
        }
        if (problemCodes.isEmpty()) {
            problemCodes = List.of("S0"); // 仅当无任何异常项时补 S0 = 未发现严重事故隐患（平台 2.6 约定）
        }
        int interval = el != null && el.intervalDays != null
                ? el.intervalDays : WORK_TYPE_INTERVAL_DAYS.getOrDefault(o.workTypeCode, 15);

        MaintainRecord r = new MaintainRecord();
        r.id = Ids.next("ur");
        r.elevatorName = el == null ? "" : el.elevatorName;
        r.elevatorCode = el == null ? "" : el.elevatorCode;
        r.workType = o.workType;
        r.workTypeCode = o.workTypeCode;
        r.workerName = o.workerName;
        r.assistantName = o.assistantName;
        r.workerPlatformId = o.workerPlatformId;
        r.assistantPlatformId = o.assistantPlatformId;
        r.checkinTime = o.checkinTime;
        r.checkoutTime = o.checkoutTime;
        r.duration = o.duration;
        r.itemsJson = JsonUtil.write(frozen);
        r.photosJson = JsonUtil.write(photos);
        // 缺省安全防护勾选时写 SQL NULL，不要写成字符串 "null"：Jackson 读 "null" 得到 null 对象，PDF 渲染 safetyLine 会 NPE（E2E 实测客户签字 500）
        r.safetyJson = body.get("safetyConfirm") == null ? null : JsonUtil.write(body.get("safetyConfirm"));
        r.todoDesc = strOrEmpty(body.get("todoDesc"));
        r.workerSignatureUrl = workerSignature;
        r.assistantSignatureUrl = assistantSignature;
        r.problemCodesJson = JsonUtil.write(problemCodes);
        r.originalRecordId = o.originalRecordId;
        r.reportStatus = o.reportStatus;
        r.retryCount = 0;
        if (rework) {
            r.version = (previous.version == null ? 1 : previous.version) + 1;
            r.prevVersionId = previous.id;
        }
        r.nextMaintenanceDate = LocalDate.now(TimeUtil.ZONE).plusDays(interval);
        r.confirmStatus = "PENDING";
        r.workOrderId = o.id;
        if (tenantSnapshotService != null) tenantSnapshotService.applyRecord(r, o, el);
        r.lifecycleStatus = recordLifecycleService == null ? null : recordLifecycleService.initialStatus();
        byte[] shareTokenBytes = new byte[16];
        SHARE_TOKEN_RANDOM.nextBytes(shareTokenBytes);
        r.shareToken = "sg" + java.util.HexFormat.of().formatHex(shareTokenBytes);
        r.createdAt = TimeUtil.now();
        r.reportPayloadJson = rework ? null : JsonUtil.write(buildReportPayload(r, el, uu));
        r.previewContextJson = JsonUtil.write(buildPreviewContext(o, el, uu, items.size(), mustRun.size()));
        // 本地落库原子化（工单 DONE + 维保记录 + 电梯滚动）——任一失败全部回滚，completeCheckout 同时以状态和本次原记录 ID 抢占，防止不同请求重复签退/重复上报。
        transactionTemplate.executeWithoutResult(tx -> {
            int completed = orderMapper.completeCheckout(o.id, o.checkoutTime, o.originalRecordId,
                    o.reportStatus, o.duration, expectedOriginalRecordId);
            if (completed != 1) {
                throw new BizException(1003, "工单已被其他请求签退，请刷新后查看");
            }
            recordMapper.insert(r);
            if (el != null) {
                el.nextMaintenanceDate = r.nextMaintenanceDate;
                el.lastMaintenanceAt = r.checkoutTime;
                elevatorMapper.updateById(el);
            }
        });
        if (o.sourceHandoverId != null && Boolean.TRUE.equals(o.handoverIssue)) {
            writeBackHandoverIssue(o);
        }
        // 复核退回整改只生成本地新版本，保留原 originalRecordId 且绝不上报。
        r.reportStatus = rework ? "DRAFT" : reportService.attemptUpload(r);
        // 最终只回写上报状态，不能用整实体覆盖复核退回刚写出的 lifecycle/工单状态。
        o.reportStatus = r.reportStatus;
        orderMapper.updateReportStatus(o.id, o.reportStatus);

        return JsonUtil.map(
                "workOrderId", o.id,
                "duration", r.duration,
                "originalRecordId", r.originalRecordId,
                "reportStatus", r.reportStatus,
                "recordId", r.id,
                "shareToken", r.shareToken);
    }

    /** 回写失败只记录日志，不回滚签退。 */
    private void writeBackHandoverIssue(WorkOrder o) {
        try {
            HandoverOrder ho = handoverOrderMapper.selectById(o.sourceHandoverId);
            if (ho == null) {
                log.warn("首保签退回写遗留问题：来源移交单 {} 不存在，跳过", o.sourceHandoverId);
                return;
            }
            ho.handoverIssue = true;
            handoverOrderMapper.updateById(ho);
            Message msg = new Message();
            msg.id = Ids.next("msg");
            msg.title = "移交遗留问题通知";
            msg.content = "首保工单 " + nz(o.orderNo) + " 已签退并记录移交遗留问题，请安装部（发起人："
                    + nz(ho.initiatorName) + "）跟进处理。";
            msg.createdAt = TimeUtil.now();
            msg.readFlag = false;
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("首保签退回写遗留问题失败（不阻断签退）：{}", e.getMessage());
        }
    }

    /** 维保记录预览上下文：与 2.6 报文分开冻结——2.6 只收汇总字段，预览需要地址/单位/人员手机号/签到经纬度/条目总数等本地留痕信息。 */
    Map<String, Object> buildPreviewContext(WorkOrder o, Elevator el, UseUnit uu, int itemTotal, int itemExecuted) {
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(422, "维保单位档案未配置，无法生成上报数据"));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("address", el == null ? "" : nz(el.location));
        ctx.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        ctx.put("companyName", nz(c.name));
        ctx.put("workerPhone", phoneByName(o.workerName, el == null ? "" : nz(el.workerPhone)));
        ctx.put("assistantPhone", phoneByName(o.assistantName, ""));
        ctx.put("workerPlatformId", nz(o.workerPlatformId));
        ctx.put("assistantPlatformId", nz(o.assistantPlatformId));
        ctx.put("checkin", checkinGeo(o.checkinExtraJson));
        ctx.put("assistantCheckin", checkinGeo(o.assistantCheckinExtraJson));
        ctx.put("itemTotal", itemTotal);
        ctx.put("itemExecuted", itemExecuted);
        return ctx;
    }

    /** 按姓名查手机号（预览展示用；档案缺失时回退传入值） */
    private String phoneByName(String name, String fallback) {
        if (isBlank(name)) {
            return nz(fallback);
        }
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getName, name)).stream().findFirst()
                .map(e -> nz(e.phone)).filter(s -> !s.isEmpty()).orElse(nz(fallback));
    }

    /** 从签到留痕 JSON 取定位要素（无留痕返回空 map，预览页按"未签到"渲染） */
    private static Map<String, Object> checkinGeo(String extraJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extraJson == null || extraJson.isEmpty()) {
            return out;
        }
        Map<String, Object> extra = JsonUtil.readMap(extraJson);
        for (String k : new String[]{"latitude", "longitude", "distance", "threshold", "geoStatus", "checkedAt"}) {
            out.put(k, extra.get(k) == null ? "" : String.valueOf(extra.get(k)));
        }
        return out;
    }

    Map<String, Object> buildReportPayload(MaintainRecord r, Elevator el, UseUnit uu) {
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(422, "维保单位档案未配置，无法生成上报数据"));
        // 按平台人员 ID 强关联取本人手机号——禁止按姓名/任意 WORKER 兜底，否则会把 B 的手机号随 A 的姓名冻结进 2.6 报文（虚假数据上平台，AGENTS §6）
        Employee recorderEmployee = requireWorkerForReport(r.workerPlatformId, "workMan1Id");
        requireWorkerForReport(r.assistantPlatformId, "workMan2Id");
        String recorderPhone = nz(recorderEmployee.phone);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("elevatorCode", nz(r.elevatorCode));
        p.put("deviceCode", el == null ? "" : nz(el.deviceCode));
        p.put("insideNumber", el == null ? "" : nz(el.insideNumber));
        p.put("unitPrincipal", uu == null ? "" : nz(uu.unitPrincipal));
        p.put("unitPrincipalPhone", uu == null ? "" : nz(uu.unitPrincipalPhone));
        p.put("elevatorAdminister", el != null && el.elevatorAdminister != null
                ? el.elevatorAdminister : (uu == null ? "" : nz(uu.elevatorAdminister)));
        p.put("elevatorAdministerPhone", el != null && el.elevatorAdministerPhone != null
                ? el.elevatorAdministerPhone : (uu == null ? "" : nz(uu.elevatorAdministerPhone)));
        p.put("emergencyPhone", el != null && el.emergencyPhone != null
                ? el.emergencyPhone : (uu == null ? "" : nz(uu.emergencyPhone)));
        p.put("workMenegerName", c.workMenegerName == null ? "" : c.workMenegerName);
        p.put("workMenegerPhone", c.workMenegerPhone == null ? "" : c.workMenegerPhone);
        p.put("workMan1Id", nz(r.workerPlatformId));
        p.put("workMan2Id", nz(r.assistantPlatformId)); // 单人作业填法待平台确认（docs/06 #3）
        p.put("startTime", TimeUtil.format(r.checkinTime));
        p.put("endTime", TimeUtil.format(r.checkoutTime));
        p.put("workType", r.workTypeCode);
        p.put("recorder", nz(r.workerName));
        p.put("recorderPhone", recorderPhone);
        p.put("originalRecordId", r.originalRecordId);
        p.put("problemCode", JsonUtil.readStringList(r.problemCodesJson));
        p.put("nextMaintenanceDate", TimeUtil.formatDate(r.nextMaintenanceDate));
        ReportPayloadValidator.requireComplete(p);
        return p;
    }

    /** 2.6 人员身份必须唯一且本人手机号完整，0 条/多条均不得取首条或任意兜底。 */
    private Employee requireWorkerForReport(String platformId, String field) {
        if (isBlank(platformId)) {
            throw new BizException(422, "2.6 必填字段为空: " + field);
        }
        List<Employee> matches = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPlatformId, platformId));
        if (matches.size() != 1 || isBlank(matches.get(0).phone)) {
            throw new BizException(422, "平台人员ID必须唯一关联一名手机号完整的员工: " + field);
        }
        return matches.get(0);
    }

    // ── 视图组装 ──
    public Map<String, Object> toMap(WorkOrder o, boolean withElevator) {
        return toMap(o, withElevator, elevatorMapper.selectById(o.elevatorId), regionNameOf(o.regionId));
    }

    private Map<String, Object> toMap(WorkOrder o, boolean withElevator, Elevator el, String regionName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("orderNo", o.orderNo);
        m.put("elevatorId", o.elevatorId);
        m.put("workType", o.workType);
        m.put("workTypeCode", o.workTypeCode);
        m.put("planTime", TimeUtil.format(o.planTime));
        m.put("status", o.status);
        m.put("regionId", nz(o.regionId));
        m.put("regionName", regionName);
        m.put("workerName", o.workerName);
        m.put("assistantName", o.assistantName);
        m.put("workerPlatformId", o.workerPlatformId);
        m.put("assistantPlatformId", o.assistantPlatformId);
        m.put("checkinTime", TimeUtil.format(o.checkinTime));
        // 双人分别签到：前端据此决定"签到/已签到"入口与动态码模式
        m.put("principalCheckedIn", hasCheckin(o.checkinExtraJson));
        m.put("assistantCheckedIn", hasCheckin(o.assistantCheckinExtraJson));
        m.put("workerSigned", o.workerSignatureFileId != null && !o.workerSignatureFileId.isBlank());
        m.put("assistantSigned", o.assistantSignatureFileId != null && !o.assistantSignatureFileId.isBlank());
        m.put("checkoutTime", TimeUtil.format(o.checkoutTime));
        m.put("duration", o.duration == null ? "" : o.duration);
        m.put("originalRecordId", o.originalRecordId == null ? "" : o.originalRecordId);
        m.put("reportStatus", o.reportStatus == null ? "" : o.reportStatus);
        if (Boolean.TRUE.equals(o.autoDispatched)) {
            m.put("autoDispatched", true);
        }
        if (withElevator) {
            m.put("checklist", items(o));
            if (el != null) {
                m.put("elevator", elevatorView(el));
            }
            if ("DONE".equals(o.status) && o.originalRecordId != null) {
                MaintainRecord rec = recordMapper.selectOne(new LambdaQueryWrapper<MaintainRecord>()
                        .eq(MaintainRecord::getOriginalRecordId, o.originalRecordId).last("LIMIT 1"));
                if (rec != null) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", rec.id);
                    info.put("shareToken", nz(rec.shareToken));
                    info.put("workerSignatureUrl", fileStorage.resolveStoredUrl(rec.workerSignatureUrl));
                    info.put("assistantSignatureUrl", fileStorage.resolveStoredUrl(rec.assistantSignatureUrl));
                    info.put("confirmStatus", nz(rec.confirmStatus));
                    info.put("uploadStatus", UnitRecordService.uploadStatus(rec.reportStatus));
                    info.put("satisfaction", rec.satisfaction);
                    m.put("recordInfo", info);
                }
            }
        } else {
            m.put("elevatorName", el == null ? "" : nz(el.elevatorName));
            m.put("elevatorCode", el == null ? "" : nz(el.elevatorCode));
            m.put("deviceCode", el == null ? "" : nz(el.deviceCode));
            m.put("regCode", el == null ? "" : nz(el.regCode));
            m.put("insideNumber", el == null ? "" : nz(el.insideNumber));
            m.put("model", el == null ? "" : nz(el.model));
            m.put("projectName", el == null ? "" : nz(el.projectName));
        }
        return m;
    }

    public Map<String, Object> elevatorView(Elevator el) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", el.id);
        e.put("elevatorCode", el.elevatorCode);
        e.put("elevatorName", el.elevatorName);
        e.put("location", el.location);
        e.put("regCode", el.regCode);
        e.put("deviceCode", el.deviceCode);
        e.put("insideNumber", el.insideNumber);
        e.put("model", el.model);
        e.put("projectName", el.projectName);
        e.put("useUnitId", el.useUnitId);
        e.put("category", el.category);
        e.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
        e.put("factoryNumber", el.factoryNumber);
        e.put("useUnitEntityId", el.useUnitEntityId);
        e.put("elevatorAdminister", el.elevatorAdminister);
        e.put("elevatorAdministerPhone", el.elevatorAdministerPhone);
        e.put("emergencyPhone", el.emergencyPhone);
        e.put("platformSyncedAt", TimeUtil.format(el.platformSyncedAt));
        e.put("lng", el.lng);
        e.put("lat", el.lat);
        e.put("checkinThreshold", el.checkinThreshold == null ? props.getCheckinThresholdMeters() : el.checkinThreshold);
        e.put("brand", el.brand);
        e.put("manufacturer", el.manufacturer);
        e.put("driveMode", el.driveMode);
        e.put("ratedLoad", el.ratedLoad);
        e.put("ratedLoadUnit", el.ratedLoadUnit);
        e.put("ratedSpeed", el.ratedSpeed);
        e.put("ratedSpeedUnit", el.ratedSpeedUnit);
        e.put("stationsDoors", el.stationsDoors);
        Map<String, Object> mt = new LinkedHashMap<>();
        mt.put("workTypeCode", el.workTypeCode);
        mt.put("intervalDays", el.intervalDays);
        mt.put("workerName", el.workerName);
        mt.put("workerPhone", el.workerPhone);
        mt.put("workerPlatformId", el.workerPlatformId);
        mt.put("assistantName", el.assistantName);
        mt.put("assistantPlatformId", el.assistantPlatformId);
        mt.put("lastMaintenanceAt", TimeUtil.format(el.lastMaintenanceAt));
        e.put("maintenance", mt);
        return e;
    }

    WorkOrderMapper orderMapper() {
        return orderMapper;
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String strOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 签名 URL：优先 fileId 解析；客户端直传的 URL 仅接受 http(s)，本地临时路径（wxfile://、http://tmp/）丢弃 */
    private String resolveSignatureUrl(Object fileId, Object legacyUrl) {
        String url = fileStorage.urlOf(strOrEmpty(fileId));
        if (!url.isEmpty()) {
            return url;
        }
        String legacy = strOrEmpty(legacyUrl);
        return isHttpUrl(legacy) ? legacy : "";
    }

    static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static Double parseNumeric(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BizException(422, "检测数值格式不正确");
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }
}
