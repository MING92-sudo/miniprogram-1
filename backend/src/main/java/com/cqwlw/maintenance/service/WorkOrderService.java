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
import com.cqwlw.maintenance.util.GeoUtil;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
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

/**
 * 工单与现场作业（docs/04 A.2 契约）：
 * 列表筛选/详情/扫码识单/签到（双人动态码）/检查项校验（TSG 注A-1/A-2）/签退
 * （时长下限 30 分钟、关键项照片留证、无隐患填 S0）→ 冻结维保记录 + 2.6 报文快照。
 * 详情与全部现场作业写端点先过 requireOrderScope（V7 班组数据权限，见该方法注释）。
 */
@Service
public class WorkOrderService {

    /** 业务规则：签到—签退间隔不少于 N 分钟（app.work-duration-minutes 默认 30，业主补充规则，上线前待确认） */
    private static final int DEFAULT_MIN_WORK_DURATION_MINUTES = 30;
    private static final Map<String, Integer> WORK_TYPE_INTERVAL_DAYS =
            Map.of("FM", 30, "HM", 15, "TM", 90, "SM", 180, "OY", 365);
    /** 双人动态码步长（秒）：docs/03 §3.2 项6「每 5 秒刷新」 */
    private static final int DYNAMIC_CODE_STEP_SECONDS = 5;
    /** 双人动态码有效期（秒）：60 秒内的码可校验；绑定工单、一次性使用（docs/05 §2.5） */
    private static final int DYNAMIC_CODE_TTL_SECONDS = 60;
    /** M7：分享令牌随机源（旧实现 Math.random 可猜，同 AdminArchiveService 口径） */
    private static final SecureRandom SHARE_TOKEN_RANDOM = new SecureRandom();

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
    private final PlatformReportService reportService;
    private final EmployeeScopeService scopeService;
    private final CheckinThresholdService thresholdService;
    private final com.cqwlw.maintenance.config.AppProperties props;
    private final FileStorageService fileStorage;
    private final TransactionTemplate transactionTemplate;

    public WorkOrderService(WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                            UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                            CompanyMapper companyMapper, MaintainRecordMapper recordMapper,
                            FaultMapper faultMapper, InspectRecordMapper inspectMapper,
                            ChecklistService checklistService, DispatchService dispatchService,
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
        this.checklistService = checklistService;
        this.dispatchService = dispatchService;
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

    // ── 首页汇总 ──
    public Map<String, Object> homeSummary(String empId) {
        dispatchService.ensureDueOrders();
        String today = TimeUtil.date(TimeUtil.now());
        String soonEnd = TimeUtil.date(TimeUtil.now().plusDays(3));
        int dueToday = 0;
        int dueSoon = 0;
        int overdue = 0;
        int inProgress = 0;
        for (WorkOrder o : scopeOrders(orderMapper.selectList(null), empId)) {
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
                .eq(Fault::getStatus, "OPEN")
                .and(w -> {
                    Set<String> creatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
                    if (creatorIds != null) {
                        w.in(Fault::getCreatedBy, creatorIds).or().isNull(Fault::getCreatedBy);
                    }
                }));
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
    public Map<String, Object> listOrders(Map<String, String> query, String empId) {
        dispatchService.ensureDueOrders();
        List<WorkOrder> list = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .orderByDesc(WorkOrder::getPlanTime));
        list = scopeOrders(list, empId);
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
            // 一次载入电梯映射，避免逐单 selectById 的 N+1
            Map<String, Elevator> elevatorMap = new java.util.HashMap<>();
            for (Elevator el : elevatorMapper.selectList(null)) {
                elevatorMap.put(el.id, el);
            }
            String k = keyword.toLowerCase();
            list = list.stream().filter(o -> {
                Elevator el = elevatorMap.get(o.elevatorId);
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

    public Map<String, Object> getOrderView(String id, String empId) {
        WorkOrder o = findOr404(id);
        requireOrderScope(o, empId);
        return toMap(o, true);
    }

    /** 班组数据权限（V7）：组长看本组，组员看本人，管理角色不限 */
    public List<WorkOrder> scopeOrders(List<WorkOrder> list, String empId) {
        Set<String> ids = scopeService.visibleWorkerPlatformIds(scopeService.require(empId));
        if (ids == null) {
            return list;
        }
        return list.stream().filter(o -> ids.contains(o.workerPlatformId)
                || ids.contains(o.assistantPlatformId)).collect(Collectors.toList());
    }

    /**
     * 工单归属校验（V7 班组数据权限）：读（详情/清单）与写（签到/检查项/签退）共用同一谓词。
     * 写端点若缺此校验，任一已登录作业人员凭他人工单 id 即可代签到、代填检查项、代签退；
     * 签退会自动转发平台 2.6，等于向监管平台写入虚假维保数据（AGENTS §6 合规底线 → Blocker）。
     */
    public void requireOrderScope(WorkOrder o, String empId) {
        Set<String> ids = scopeService.visibleWorkerPlatformIds(scopeService.require(empId));
        if (ids != null && !ids.contains(o.workerPlatformId) && !ids.contains(o.assistantPlatformId)) {
            throw new BizException(1403, "仅可查看或操作本人工单或本班组工单");
        }
    }

    public Map<String, Object> resolveByElevator(String elevatorCode, String empId) {
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
        requireOrderScope(o, empId); // C3：与其它端点同样校验班组归属，防跨班组越权读取
        return toMap(o, true);
    }

    // ── 签到 ──

    /**
     * 签到（docs/01 §3.7.2「主维保人员和配合人员分别签到」+ docs/02 §5.4 定位阈值）：
     * ①定位坐标必填（缺失 422；合规底线：定位失败如实提示，严禁伪造坐标兜底）；
     * ②电梯已登记坐标 → Haversine 距离超阈值拦截 1001；坐标未登记 → geoStatus=UNKNOWN，只留痕不拦截（码表 1005 语义）；
     * ③配合人员须携带主维保生成、未过期且未使用的动态码（1003）；
     * ④主维保/配合人员各自留痕（checkin_extra / assistant_checkin_extra），第二人签到不覆盖第一人。
     */
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
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        // 动态码校验与消费在任何写操作之前：校验失败不产生副作用（不落库、不改状态）
        if (assistant) {
            consumeDynamicCode(o, str(body.get("dynamicCode")));
        }
        // 定位校验（docs/02 §5.4）：电梯坐标缺失 → UNKNOWN，只留痕不拦截（docs/04 A.0.1 码表 1005）
        String geoStatus = "UNKNOWN";
        double distance = -1d;
        // 阈值三级解析：电梯级 → 品种级（sys_param）→ 全局（sys_param → app 配置），docs/02 §5.4
        int threshold = thresholdService.resolve(el);
        if (el != null && el.lat != null && el.lng != null) {
            geoStatus = el.geoStatus == null || el.geoStatus.isEmpty() ? "PROVIDED" : el.geoStatus;
            distance = GeoUtil.distanceMeters(lat, lng, el.lat.doubleValue(), el.lng.doubleValue());
            // M11：SELF_COLLECTED 基准由首个签到者 GPS 自证回填，不作为围栏拦截依据（仅留痕，1005 语义）
            if (!"SELF_COLLECTED".equals(el.geoStatus) && distance > threshold) {
                throw new BizException(1001, "签到位置超出允许范围：距电梯 " + round1(distance)
                        + " 米，阈值 " + threshold + " 米；请到现场后重试");
            }
        }
        String collectedAt = str(body.get("collectedAt"));
        LocalDateTime checkinTime = collectedAt != null && !collectedAt.isEmpty()
                ? TimeUtil.parse(collectedAt) : TimeUtil.now();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("latitude", body.get("latitude"));
        extra.put("longitude", body.get("longitude"));
        extra.put("locationAccuracy", body.get("locationAccuracy") == null ? 0 : body.get("locationAccuracy"));
        extra.put("role", assistant ? "ASSISTANT" : "PRINCIPAL");
        extra.put("dynamicCode", strOrEmpty(body.get("dynamicCode")));
        extra.put("selfPhotoFileId", strOrEmpty(body.get("photoFileId")));
        extra.put("distance", distance < 0 ? "" : String.valueOf(round1(distance)));
        extra.put("threshold", distance < 0 ? "" : String.valueOf(threshold));
        extra.put("geoStatus", geoStatus);
        extra.put("checkedAt", TimeUtil.format(checkinTime));
        if (assistant) {
            o.assistantCheckinExtraJson = JsonUtil.write(extra);
        } else {
            o.checkinExtraJson = JsonUtil.write(extra);
        }
        if (o.checkinTime == null) {
            o.checkinTime = checkinTime;
        }
        o.status = "PROCESSING";
        orderMapper.updateById(o);
        // 用户需求③：电梯无坐标时，以签到定位自动回填电梯坐标档案（坐标来源=现场采集，docs/02 §5.4）
        boolean geoBackfilled = false;
        if (el != null && (el.lng == null || el.lat == null)) {
            el.lng = java.math.BigDecimal.valueOf(lng);
            el.lat = java.math.BigDecimal.valueOf(lat);
            el.geoStatus = "SELF_COLLECTED";
            elevatorMapper.updateById(el);
            geoBackfilled = true;
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

    // ── 双人动态码（docs/03 §3.2 项6：60 秒有效、前端每 5 秒刷新倒计时；绑定工单、一次性使用） ──

    /**
     * 主维保生成/刷新动态码（docs/03 §3.2 项6：每 5 秒刷新、60 秒有效）。
     * 码由 HMAC(密钥, 工单ID|时间步长) 派生：绑定工单、无需落库即可轮换，密钥取 app.jwt-secret（不打印）。
     */
    public Map<String, Object> issueDynamicCode(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
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

    /** 校验并消费（一次性）：记下已用码防重放；置空串而非 null（MyBatis-Plus 默认策略不更新 null 字段） */
    private void consumeDynamicCode(WorkOrder o, String code) {
        requireDynamicCode(o, code);
        o.dynamicCode = code;
        o.dynamicCodeExpiresAt = TimeUtil.now().plusSeconds(DYNAMIC_CODE_TTL_SECONDS);
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
        // M13 已知取舍：GET 详情路径会在此懒生成并 updateById 清单（读路径写库）。
        // 功能正确、移动生成时机涉及派单/模板链路回归，风险大于收益，保留现状。
        if (o.checklistJson == null || o.checklistJson.isEmpty()) {
            Elevator el = elevatorMapper.selectById(o.elevatorId);
            String category = el == null ? "" : el.category;
            String specialType = el == null || el.specialType == null ? "" : el.specialType;
            o.checklistJson = JsonUtil.write(checklistService.buildChecklist(o.workTypeCode, category, specialType));
            orderMapper.updateById(o);
        }
        return JsonUtil.readList(o.checklistJson);
    }

    public Map<String, Object> getChecklist(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
        return JsonUtil.map("checklistId", o.id, "items", items(o));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitItem(String orderId, String itemId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
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
        item.put("value", parseNumeric(body.get("value"))); // M14：数字/数字字符串放行，非法类型 422
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

    public Map<String, Object> runThisTime(String orderId, String itemId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
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
    public Map<String, Object> checkout(String orderId, Map<String, Object> body, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
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
        int minMinutes = minWorkDurationMinutes();
        long minutes = o.checkinTime == null ? minMinutes
                : ChronoUnit.MINUTES.between(o.checkinTime, checkoutTime);
        if (minutes < minMinutes) {
            throw new BizException(422, "作业时长不足 " + minMinutes + " 分钟（当前 " + minutes
                    + " 分钟），请继续作业后再签退");
        }

        o.status = "DONE";
        o.checkoutTime = checkoutTime;
        o.originalRecordId = Ids.nextRecordId();
        o.reportStatus = "SUBMITTED";
        o.duration = TimeUtil.formatDuration(TimeUtil.toMillis(checkoutTime) - TimeUtil.toMillis(o.checkinTime));

        Elevator el = elevatorMapper.selectById(o.elevatorId);
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        List<Map<String, Object>> frozen = items.stream().map(i -> new LinkedHashMap<>(i)).collect(Collectors.toList());
        // 照片以 fileId 解析出的可访问 URL 为准；历史/演示数据的本地临时路径（wxfile://、http://tmp/）不入档（2026-10-05 管理端乱图）
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
        r.safetyJson = JsonUtil.write(body.get("safetyConfirm"));
        r.todoDesc = strOrEmpty(body.get("todoDesc"));
        r.workerSignatureUrl = resolveSignatureUrl(body.get("signatureFileId"), body.get("signatureUrl"));
        r.assistantSignatureUrl = resolveSignatureUrl(body.get("assistantSignatureFileId"), body.get("assistantSignatureUrl"));
        r.problemCodesJson = JsonUtil.write(problemCodes);
        r.originalRecordId = o.originalRecordId;
        r.reportStatus = o.reportStatus;
        r.retryCount = 0;
        r.nextMaintenanceDate = LocalDate.now(TimeUtil.ZONE).plusDays(interval);
        r.confirmStatus = "PENDING";
        // M7：SecureRandom 128bit——旧实现时间36进制+Math.random 可猜，令牌可写签名与满意度
        byte[] shareTokenBytes = new byte[16];
        SHARE_TOKEN_RANDOM.nextBytes(shareTokenBytes);
        r.shareToken = "sg" + java.util.HexFormat.of().formatHex(shareTokenBytes);
        r.createdAt = TimeUtil.now();
        r.reportPayloadJson = JsonUtil.write(buildReportPayload(r, el, uu));
        r.previewContextJson = JsonUtil.write(buildPreviewContext(o, el, uu, items.size(), mustRun.size()));
        // B3：本地落库原子化（工单 DONE + 维保记录 + 电梯滚动）——任一失败全部回滚，
        // 避免"工单已完成但记录丢失"的合规证据链断裂；平台上传移出事务避免长事务持锁
        transactionTemplate.executeWithoutResult(tx -> {
            orderMapper.updateById(o);
            recordMapper.insert(r);
            if (el != null) {
                el.nextMaintenanceDate = r.nextMaintenanceDate;
                el.lastMaintenanceAt = r.checkoutTime;
                elevatorMapper.updateById(el);
            }
        });
        // P3：签退成功后自动转发平台 2.6（失败不自动重试，AGENTS §2.3；
        // 平台凭证未配置时保持 SUBMITTED=待上报，本地/演示流程不受影响）
        r.reportStatus = reportService.attemptUpload(r);
        recordMapper.updateById(r);
        o.reportStatus = r.reportStatus;
        orderMapper.updateById(o);

        return JsonUtil.map(
                "workOrderId", o.id,
                "duration", r.duration,
                "originalRecordId", r.originalRecordId,
                "reportStatus", r.reportStatus,
                "recordId", r.id,
                "shareToken", r.shareToken);
    }

    /**
     * 维保记录预览上下文（docs/03 §六 信息完整性清单）：与 2.6 报文分开冻结——
     * 2.6 只收汇总字段，预览需要地址/单位/人员手机号/签到经纬度/条目总数等本地留痕信息。
     */
    Map<String, Object> buildPreviewContext(WorkOrder o, Elevator el, UseUnit uu, int itemTotal, int itemExecuted) {
        // B2：单维保单位约定下取唯一档案；表空即建档缺失，宁可中断也不上报空负责人
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

    /** 平台 2.6 报文快照（20 字段冻结；workMeneger 拼写按规范原文，docs/04 B.6） */
    Map<String, Object> buildReportPayload(MaintainRecord r, Elevator el, UseUnit uu) {
        // B2：单维保单位约定下取唯一档案；表空即建档缺失，宁可中断也不上报空负责人
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(422, "维保单位档案未配置，无法生成上报数据"));
        // B1：按平台人员 ID 强关联取本人手机号——禁止按姓名/任意 WORKER 兜底，
        // 否则会把 B 的手机号随 A 的姓名冻结进 2.6 报文（虚假数据上平台，AGENTS §6）
        String recorderPhone = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPlatformId, r.workerPlatformId)).stream().findFirst()
                .map(e -> nz(e.phone)).orElse("");
        if (recorderPhone.isEmpty()) {
            throw new BizException(422, "员工平台人员ID未关联或缺少手机号，无法上报");
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
        p.put("problemCode", JsonUtil.readStringList(r.problemCodesJson));
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
        // 双人分别签到（docs/01 §3.7.2）：前端据此决定"签到/已签到"入口与动态码模式
        m.put("principalCheckedIn", hasCheckin(o.checkinExtraJson));
        m.put("assistantCheckedIn", hasCheckin(o.assistantCheckinExtraJson));
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
        // 电梯级签到阈值（米）；空=前端按全局默认展示（docs/02 §5.4）
        e.put("checkinThreshold", el.checkinThreshold == null ? props.getCheckinThresholdMeters() : el.checkinThreshold);
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

    /** M14：检测数值接受 Number/数字字符串；其余 422（旧实现直接强转，前端传字符串即 500） */
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
