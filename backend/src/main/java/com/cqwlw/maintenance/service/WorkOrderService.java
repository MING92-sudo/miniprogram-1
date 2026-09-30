package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工单与现场作业（docs/04 A.2 契约）：
 * 列表筛选/详情/扫码识单/签到（双人动态码）/检查项校验（TSG 注A-1/A-2）/签退
 * （时长下限 30 分钟、关键项照片留证、无隐患填 S0）→ 冻结维保记录 + 2.6 报文快照。
 */
@Service
public class WorkOrderService {

    /** 业务规则：签到—签退间隔不少于 30 分钟（constants MIN_WORK_DURATION_MINUTES 口径） */
    private static final int MIN_WORK_DURATION_MINUTES = 30;
    private static final Map<String, Integer> WORK_TYPE_INTERVAL_DAYS =
            Map.of("FM", 30, "HM", 15, "TM", 90, "SM", 180, "OY", 365);
    /** 演示约定：双人动态码固定 888888（与 mock 行为一致） */
    private static final String DYNAMIC_CODE = "888888";

    private final WorkOrderMapper orderMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final CompanyMapper companyMapper;
    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final InspectRecordMapper inspectMapper;
    private final ChecklistService checklistService;
    private final DispatchService dispatchService;

    public WorkOrderService(WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                            UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                            CompanyMapper companyMapper, MaintainRecordMapper recordMapper,
                            FaultMapper faultMapper, InspectRecordMapper inspectMapper,
                            ChecklistService checklistService, DispatchService dispatchService) {
        this.orderMapper = orderMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.companyMapper = companyMapper;
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.inspectMapper = inspectMapper;
        this.checklistService = checklistService;
        this.dispatchService = dispatchService;
    }

    // ── 首页汇总 ──
    public Map<String, Object> homeSummary() {
        dispatchService.ensureDueOrders();
        String today = TimeUtil.date(TimeUtil.now());
        String soonEnd = TimeUtil.date(TimeUtil.now().plusDays(3));
        int dueToday = 0;
        int dueSoon = 0;
        int overdue = 0;
        int inProgress = 0;
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
        }
        long unconfirmed = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getConfirmStatus, "PENDING"));
        long openFaults = faultMapper.selectCount(new LambdaQueryWrapper<Fault>()
                .eq(Fault::getStatus, "OPEN"));
        // 年检预警：自行检查逾期未检台数（须在下次定期检验前完成，docs/01 §3.17）
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
        m.put("unconfirmed", unconfirmed);
        m.put("platformTotal", elevatorMapper.selectCount(null));
        m.put("openFaults", openFaults);
        m.put("overdueInspects", overdueInspects);
        m.put("warnCount", dueToday + overdue);
        return m;
    }

    // ── 列表 ──
    public Map<String, Object> listOrders(Map<String, String> query) {
        dispatchService.ensureDueOrders();
        List<WorkOrder> list = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .orderByDesc(WorkOrder::getPlanTime));
        String due = query.get("due");
        if (due != null && !due.isEmpty()) {
            String today = TimeUtil.date(TimeUtil.now());
            String soonEnd = TimeUtil.date(TimeUtil.now().plusDays(3));
            list = list.stream().filter(o -> {
                String day = o.planTime == null ? "" : TimeUtil.date(o.planTime);
                if (day.isEmpty() || "DONE".equals(o.status)) {
                    return false;
                }
                switch (due) {
                    case "today":
                        return day.equals(today);
                    case "soon":
                        return day.compareTo(today) > 0 && day.compareTo(soonEnd) <= 0;
                    case "overdue":
                        return day.compareTo(today) < 0;
                    default:
                        return true;
                }
            }).collect(Collectors.toList());
        }
        String status = query.get("status");
        if (status != null && !status.isEmpty()) {
            list = list.stream().filter(o -> status.equals(o.status)).collect(Collectors.toList());
        }
        String keyword = query.get("keyword");
        if (keyword != null && !keyword.isEmpty()) {
            String k = keyword.toLowerCase();
            list = list.stream().filter(o -> {
                Elevator el = elevatorMapper.selectById(o.elevatorId);
                return contains(o.orderNo, k) || (el != null && (contains(el.elevatorName, k)
                        || contains(el.elevatorCode, k) || contains(el.deviceCode, k)
                        || contains(el.regCode, k) || contains(el.insideNumber, k)));
            }).collect(Collectors.toList());
        }
        List<Map<String, Object>> views = list.stream().map(o -> toMap(o, false)).collect(Collectors.toList());
        return paginate(views, query);
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

    public Map<String, Object> getOrderView(String id) {
        WorkOrder o = findOr404(id);
        return toMap(o, true);
    }

    public Map<String, Object> resolveByElevator(String elevatorCode) {
        dispatchService.ensureDueOrders();
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
        return toMap(o, true);
    }

    // ── 签到 ──
    public Map<String, Object> checkin(String orderId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        if (!"PENDING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许签到");
        }
        if ("ASSISTANT".equals(body.get("role"))) {
            String code = str(body.get("dynamicCode"));
            if (code == null || code.isEmpty()) {
                throw new BizException(422, "配合人员签到必须携带双人动态码");
            }
            if (!DYNAMIC_CODE.equals(code)) {
                throw new BizException(1003, "动态码错误（演示环境固定为 888888）");
            }
        }
        String collectedAt = str(body.get("collectedAt"));
        o.status = "PROCESSING";
        o.checkinTime = collectedAt != null && !collectedAt.isEmpty()
                ? TimeUtil.parse(collectedAt) : TimeUtil.now();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("latitude", body.get("latitude"));
        extra.put("longitude", body.get("longitude"));
        extra.put("locationAccuracy", body.get("locationAccuracy") == null ? 0 : body.get("locationAccuracy"));
        extra.put("role", body.get("role") == null ? "PRINCIPAL" : body.get("role"));
        extra.put("dynamicCode", strOrEmpty(body.get("dynamicCode")));
        extra.put("selfPhotoFileId", strOrEmpty(body.get("photoFileId")));
        o.checkinExtraJson = JsonUtil.write(extra);
        orderMapper.updateById(o);
        return JsonUtil.map(
                "checkinId", Ids.next("chk"),
                "distance", 35.6,
                "threshold", 200,
                "passed", true,
                "geoStatus", "PROVIDED");
    }

    public Map<String, Object> verifyDynamicCode(Map<String, Object> body) {
        if (!DYNAMIC_CODE.equals(str(body.get("code")))) {
            throw new BizException(1003, "动态码错误（演示环境固定为 888888）");
        }
        return JsonUtil.map("ok", true);
    }

    // ── 检查项 ──
    public List<Map<String, Object>> items(WorkOrder o) {
        if (o.checklistJson == null || o.checklistJson.isEmpty()) {
            Elevator el = elevatorMapper.selectById(o.elevatorId);
            String category = el == null ? "" : el.category;
            o.checklistJson = JsonUtil.write(checklistService.buildChecklist(o.workTypeCode, category));
            orderMapper.updateById(o);
        }
        return JsonUtil.readList(o.checklistJson);
    }

    public Map<String, Object> getChecklist(String orderId) {
        WorkOrder o = findOr404(orderId);
        return JsonUtil.map("checklistId", o.id, "items", items(o));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitItem(String orderId, String itemId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        List<Map<String, Object>> items = items(o);
        Map<String, Object> item = items.stream()
                .filter(i -> itemId.equals(i.get("id"))).findFirst()
                .orElseThrow(() -> new BizException(1404, "检查项不存在"));
        String result = str(body.get("result"));
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
        boolean isKey = Boolean.TRUE.equals(item.get("isKey"));
        boolean photoRequired = Boolean.TRUE.equals(item.get("photoRequired"));
        if (isKey && photoRequired && !"NA".equals(result) && !hasPhoto) {
            throw new BizException(422, "关键项「" + name + "」为试验/测试/校验/检测类，必须至少附 1 张照片留证（TSG 注A-2）");
        }
        item.put("result", result);
        item.put("value", body.get("value") != null ? ((Number) body.get("value")).doubleValue() : null);
        item.put("valueText", strOrEmpty(body.get("valueText")));
        item.put("abnormalDesc", strOrEmpty(body.get("abnormalDesc")));
        item.put("skipReason", strOrEmpty(body.get("skipReason")));
        item.put("problemCode", strOrEmpty(body.get("problemCode")));
        item.put("photos", body.get("photoUrls") == null ? new ArrayList<>() : body.get("photoUrls"));
        item.put("photoFileIds", body.get("photoFileIds") == null ? new ArrayList<>() : body.get("photoFileIds"));
        String recordedAt = str(body.get("recordedAt"));
        item.put("recordedAt", recordedAt == null || recordedAt.isEmpty()
                ? TimeUtil.format(TimeUtil.now()) : recordedAt);
        o.checklistJson = JsonUtil.write(items);
        orderMapper.updateById(o);
        return JsonUtil.map("ok", true, "itemId", itemId);
    }

    public Map<String, Object> runThisTime(String orderId, String itemId) {
        WorkOrder o = findOr404(orderId);
        List<Map<String, Object>> items = items(o);
        Map<String, Object> item = items.stream()
                .filter(i -> itemId.equals(i.get("id"))).findFirst()
                .orElseThrow(() -> new BizException(1404, "检查项不存在"));
        item.put("notInThisRun", false);
        o.checklistJson = JsonUtil.write(items);
        orderMapper.updateById(o);
        return JsonUtil.map("ok", true);
    }

    // ── 签退 ──
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkout(String orderId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "请先完成签到");
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
        long minutes = o.checkinTime == null ? MIN_WORK_DURATION_MINUTES
                : ChronoUnit.MINUTES.between(o.checkinTime, checkoutTime);
        if (minutes < MIN_WORK_DURATION_MINUTES) {
            throw new BizException(422, "作业时长不足 30 分钟（当前 " + minutes
                    + " 分钟），请继续作业后再签退");
        }

        o.status = "DONE";
        o.checkoutTime = checkoutTime;
        o.originalRecordId = Ids.nextRecordId();
        o.reportStatus = "SUBMITTED";
        o.duration = TimeUtil.formatDuration(TimeUtil.toMillis(checkoutTime) - TimeUtil.toMillis(o.checkinTime));
        orderMapper.updateById(o);

        Elevator el = elevatorMapper.selectById(o.elevatorId);
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        List<Map<String, Object>> frozen = items.stream().map(i -> new LinkedHashMap<>(i)).collect(Collectors.toList());
        List<String> photos = frozen.stream()
                .flatMap(i -> asList(i.get("photos")).stream().map(String::valueOf))
                .collect(Collectors.toList());
        List<String> problemCodes = frozen.stream()
                .filter(i -> "ABNORMAL".equals(String.valueOf(i.get("result")))
                        && !String.valueOf(i.get("problemCode")).isEmpty())
                .map(i -> String.valueOf(i.get("problemCode"))).collect(Collectors.toList());
        if (problemCodes.isEmpty()) {
            problemCodes = List.of("S0"); // 无隐患必须填 S0（平台 2.6 约定）
        }
        int interval = WORK_TYPE_INTERVAL_DAYS.getOrDefault(o.workTypeCode, 15);

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
        r.workerSignatureUrl = strOrEmpty(body.get("signatureUrl"));
        r.assistantSignatureUrl = strOrEmpty(body.get("assistantSignatureUrl"));
        r.problemCodesJson = JsonUtil.write(problemCodes);
        r.originalRecordId = o.originalRecordId;
        r.reportStatus = o.reportStatus;
        r.retryCount = 0;
        r.nextMaintenanceDate = LocalDate.now(TimeUtil.ZONE).plusDays(interval);
        r.confirmStatus = "PENDING";
        r.shareToken = "sg" + Long.toString(System.currentTimeMillis(), 36)
                + Long.toString((long) (Math.random() * 1e8), 36);
        r.createdAt = TimeUtil.now();
        r.reportPayloadJson = JsonUtil.write(buildReportPayload(r, el, uu));
        recordMapper.insert(r);

        return JsonUtil.map(
                "workOrderId", o.id,
                "duration", r.duration,
                "originalRecordId", r.originalRecordId,
                "reportStatus", r.reportStatus,
                "recordId", r.id,
                "shareToken", r.shareToken);
    }

    /** 平台 2.6 报文快照（20 字段冻结；workMeneger 拼写按规范原文，docs/04 B.6） */
    Map<String, Object> buildReportPayload(MaintainRecord r, Elevator el, UseUnit uu) {
        Company c = companyMapper.selectList(null).stream().findFirst().orElse(new Company());
        String recorderPhone = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getName, r.workerName)).stream().findFirst()
                .map(e -> e.phone).orElse("");
        if (recorderPhone.isEmpty()) {
            recorderPhone = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                    .eq(Employee::getRole, "WORKER")).stream().findFirst()
                    .map(e -> e.phone).orElse("");
        }
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
        p.put("problemCode", r.problemCodesJson == null ? List.of() : JsonUtil.readList(r.problemCodesJson));
        p.put("nextMaintenanceDate", TimeUtil.formatDate(r.nextMaintenanceDate));
        return p;
    }

    // ── 视图组装 ──
    public Map<String, Object> toMap(WorkOrder o, boolean withElevator) {
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("orderNo", o.orderNo);
        m.put("elevatorId", o.elevatorId);
        m.put("workType", o.workType);
        m.put("workTypeCode", o.workTypeCode);
        m.put("planTime", TimeUtil.format(o.planTime));
        m.put("status", o.status);
        m.put("workerName", o.workerName);
        m.put("assistantName", o.assistantName);
        m.put("workerPlatformId", o.workerPlatformId);
        m.put("assistantPlatformId", o.assistantPlatformId);
        m.put("checkinTime", TimeUtil.format(o.checkinTime));
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
                    info.put("workerSignatureUrl", nz(rec.workerSignatureUrl));
                    info.put("assistantSignatureUrl", nz(rec.assistantSignatureUrl));
                    info.put("confirmStatus", nz(rec.confirmStatus));
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
            m.put("projectName", uu == null ? "" : nz(uu.unitName));
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
        e.put("brand", el.brand);
        e.put("manufacturer", el.manufacturer);
        e.put("productNo", el.productNo);
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

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }
}
