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
import org.springframework.transaction.annotation.Transactional;

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

    /** 业务规则：签到—签退间隔不少于 N 分钟（app.work-duration-minutes 默认 30，业主补充规则，上线前待确认） */
    private static final int DEFAULT_MIN_WORK_DURATION_MINUTES = 30;
    private static final Map<String, Integer> WORK_TYPE_INTERVAL_DAYS =
            Map.of("FM", 30, "HM", 15, "TM", 90, "SM", 180, "OY", 365);
    /** 演示约定：双人动态码固定 888888（与 mock 行为一致） */
    private static final String DYNAMIC_CODE = "888888";
    /** 签到地理围栏阈值（米）；与 mock 契约 threshold=200 一致，1001 码见 docs/04 A.0.1 */
    private static final int CHECKIN_DISTANCE_LIMIT_M = 200;
    /**
     * 严重事故隐患码 S0—S7（平台 V1.5 规范 3.2，与前端 constants PROBLEM_CODES 同源）。
     * S0「未发现严重事故隐患」是合法取值：检查项异常但不构成严重隐患时填 S0；
     * 只有**没有任何异常检查项**时签退才自动补 S0。异常项一律要求显式记录判定，
     * 杜绝"有异常却报未发现隐患"的假数据上报监管平台。
     */
    private static final java.util.Set<String> PROBLEM_CODES = new java.util.HashSet<>(
            java.util.Arrays.asList("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7"));

    private final WorkOrderMapper orderMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final CompanyMapper companyMapper;
    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final InspectRecordMapper inspectMapper;
    private final ChecklistService checklistService;
    private final ApprovalService approvalService;
    private final EvidenceTokenService evidenceTokenService;
    private final FileStorageService fileStorageService;
    private final PlatformReportService reportService;
    private final com.cqwlw.maintenance.config.AppProperties props;

    public WorkOrderService(WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                            UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                            CompanyMapper companyMapper, MaintainRecordMapper recordMapper,
                            FaultMapper faultMapper, InspectRecordMapper inspectMapper,
                            ChecklistService checklistService,
                            ApprovalService approvalService, EvidenceTokenService evidenceTokenService,
                            FileStorageService fileStorageService,
                            PlatformReportService reportService,
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
        this.approvalService = approvalService;
        this.evidenceTokenService = evidenceTokenService;
        this.fileStorageService = fileStorageService;
        this.reportService = reportService;
        this.props = props;
    }

    private int minWorkDurationMinutes() {
        int v = props.getWorkDurationMinutes();
        return v < 0 ? DEFAULT_MIN_WORK_DURATION_MINUTES : v;
    }

    // ── 首页汇总 ──
    // 读接口不触发派单：派单只由定时任务（09:00 + 13分钟兜底）与管理端显式操作驱动，
    // 否则并发打开首页会造成重复派单，并使读接口偶发失败（审查 B6/B7）
    public Map<String, Object> homeSummary() {
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
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        //取证令牌：必须在拍照前由服务端签发，绑定已通过围栏校验的坐标与服务端时间。
        // 验签+一次性核销通过后，签到时间与坐标一律采用令牌内值，客户端自填值仅留存作对账。
        Map<String, Object> evidence = evidenceTokenService.verifyAndConsume(
                str(body.get("evidenceToken")), orderId);
        Double lat = EvidenceTokenService.latOf(evidence);
        Double lng = EvidenceTokenService.lngOf(evidence);
        boolean appealApproved = approvalService.hasApproved(orderId);
        String geoStatus = "EVIDENCE_VERIFIED";
        // 签到时间以服务端时间为准：上报监管平台的 startTime 必须是服务端可举证时间。
        // 客户端 collectedAt 不可信，仅留存作离线补传对账（docs/04 A.2 collectedAt 语义修订）。
        LocalDateTime receivedAt = TimeUtil.now();
        o.status = "PROCESSING";
        o.checkinTime = receivedAt;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("latitude", lat);
        extra.put("longitude", lng);
        extra.put("locationAccuracy", body.get("locationAccuracy") == null ? 0 : body.get("locationAccuracy"));
        extra.put("role", body.get("role") == null ? "PRINCIPAL" : body.get("role"));
        extra.put("dynamicCode", strOrEmpty(body.get("dynamicCode")));
        extra.put("selfPhotoFileId", strOrEmpty(body.get("photoFileId")));
        extra.put("collectedAtClaimed", strOrEmpty(body.get("collectedAt")));
        extra.put("receivedAt", TimeUtil.format(receivedAt));
        extra.put("evidenceIssuedAt", TimeUtil.format(TimeUtil.fromMillis(numOf(evidence.get("st")) * 1000L)));
        extra.put("threshold", CHECKIN_DISTANCE_LIMIT_M);
        extra.put("geoStatus", geoStatus);
        extra.put("locationAppealApproved", appealApproved);
        o.checkinExtraJson = JsonUtil.write(extra);
        orderMapper.updateById(o);
        return JsonUtil.map(
                "checkinId", Ids.next("chk"),
                "threshold", CHECKIN_DISTANCE_LIMIT_M,
                "passed", true,
                "geoStatus", geoStatus);
    }

    /**
     * 签到取证令牌（拍照前调用）：校验工单状态与地理围栏，通过后签发绑定
     * {工单, 已校验坐标, 服务端时间, 一次性随机数} 的签名令牌，供水印相机渲染与签到核销。
     */
    public Map<String, Object> issueEvidence(String orderId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        if (!"PENDING".equals(o.status)) {
            throw new BizException(1003, "当前状态不允许签到");
        }
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        Double lat = dbl(body.get("latitude"));
        Double lng = dbl(body.get("longitude"));
        Fence fence = checkFence(orderId, el, lat, lng);
        if (!fence.elevatorLocated) {
            // 1005 降级放行：电梯未登记坐标时无法判定围栏
            return evidenceTokenService.issue(orderId, 0d, 0d, -1, CHECKIN_DISTANCE_LIMIT_M);
        }
        return evidenceTokenService.issue(orderId, lat, lng, Math.round(fence.distance),
                CHECKIN_DISTANCE_LIMIT_M);
    }

    /**
     * 检查项拍照取证令牌（拍照前调用）：绑定 {工单, 检查项, 已校验坐标, 服务端时间, 一次性随机数}。
     * 工单须已签到（PROCESSING）——检查项照片是"作业过程中的设备细节证据"，以到场为前提。
     */
    public Map<String, Object> issueShotEvidence(String orderId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "请先完成签到后再拍摄检查项照片");
        }
        String itemId = str(body.get("itemId"));
        if (itemId == null || itemId.isEmpty()) {
            throw new BizException(422, "缺少检查项 id");
        }
        boolean exists = items(o).stream().anyMatch(i -> itemId.equals(i.get("id")));
        if (!exists) {
            throw new BizException(1404, "检查项不存在");
        }
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        Double lat = dbl(body.get("latitude"));
        Double lng = dbl(body.get("longitude"));
        Fence fence = checkFence(orderId, el, lat, lng);
        long dist = fence.elevatorLocated ? Math.round(fence.distance) : -1;
        return evidenceTokenService.issue(orderId, itemId,
                lat == null ? 0d : lat, lng == null ? 0d : lng, dist,
                CHECKIN_DISTANCE_LIMIT_M, EvidenceTokenService.TTL_SHOT_SECONDS);
    }

    /**
     * 签名取证令牌（签署前调用）：绑定 {工单, kind=sign, 角色, 服务端时间}。
     * 签名可发生在电梯之外（使用单位远程签字），故不做地理围栏，改以"角色 + 服务端时间"取证。
     */
    public Map<String, Object> issueSignEvidence(String orderId, Map<String, Object> body) {
        WorkOrder o = findOr404(orderId);
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "请先完成签到后再签署");
        }
        String role = "ASSISTANT".equals(str(body.get("role"))) ? "ASSISTANT" : "PRINCIPAL";
        return evidenceTokenService.issueSign(orderId, role, EvidenceTokenService.TTL_SHOT_SECONDS);
    }

    /**
     * 校验签名取证令牌并返回签名存证；角色必须与令牌一致。
     * 幂等验签（不核销），使"签退提交超时 → 重试"可成功。
     */
    private Map<String, Object> verifySignEvidence(String orderId, String role, Object tokenObj) {
        String token = str(tokenObj);
        if (isBlank(token)) {
            throw new BizException(422, "缺少" + signRoleText(role) + "签名取证令牌，请重新签署");
        }
        Map<String, Object> payload = evidenceTokenService.verifyOnly(token, orderId, null,
                EvidenceTokenService.KIND_SIGN, role);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("role", role);
        rec.put("signedAt", TimeUtil.format(TimeUtil.fromMillis(numOf(payload.get("st")) * 1000L)));
        rec.put("evidenceNonce", str(payload.get("n")));
        return rec;
    }

    private static String signRoleText(String role) {
        return "ASSISTANT".equals(role) ? "配合人员" : "主维保人员";
    }

    private static final class Fence {
        final boolean elevatorLocated;
        final double distance;
        final boolean inRange;
        final boolean appealApproved;

        Fence(boolean elevatorLocated, double distance, boolean inRange, boolean appealApproved) {
            this.elevatorLocated = elevatorLocated;
            this.distance = distance;
            this.inRange = inRange;
            this.appealApproved = appealApproved;
        }
    }

    /**
     * 地理围栏校验：电梯已登记坐标且客户端越界、且无已通过申诉时抛 1001（docs/04 A.0.1）。
     * 电梯未登记坐标则降级放行（1005），但不得伪装成已核验。
     */
    private Fence checkFence(String orderId, Elevator el, Double lat, Double lng) {
        Double elLat = el == null || el.lat == null ? null : el.lat.doubleValue();
        Double elLng = el == null || el.lng == null ? null : el.lng.doubleValue();
        boolean elLocated = elLat != null && elLng != null;
        boolean clientLocated = lat != null && lng != null;
        double distance = (elLocated && clientLocated) ? distanceMeters(lat, lng, elLat, elLng) : -1;
        boolean inRange = distance >= 0 && distance <= CHECKIN_DISTANCE_LIMIT_M;
        boolean appealApproved = approvalService.hasApproved(orderId);
        if (elLocated && !clientLocated) {
            throw geoRejected(null);
        }
        if (elLocated && !inRange && !appealApproved) {
            throw geoRejected(Math.round(distance));
        }
        return new Fence(elLocated, distance, inRange, appealApproved);
    }

    private static long numOf(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 1001 地理围栏拒绝：回显实测距离与阈值，前端据此引导申诉（docs/04 A.0.1） */
    private static BizException geoRejected(Long meters) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("distance", meters == null ? "" : meters);
        data.put("threshold", CHECKIN_DISTANCE_LIMIT_M);
        data.put("appealable", true);
        String msg = meters == null
                ? "未获取到定位，无法验证作业地点，请开启定位后重试"
                : "签到位置超出允许范围（" + meters + " 米 > " + CHECKIN_DISTANCE_LIMIT_M + " 米），请提交申诉";
        return new BizException(1001, msg, data);
    }

    /** Haversine 球面距离（米）；坐标缺失返回 -1 表示不可判定 */
    private static double distanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return -1;
        }
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
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
            String specialType = el == null || el.specialType == null ? "" : el.specialType;
            o.checklistJson = JsonUtil.write(checklistService.buildChecklist(o.workTypeCode, category, specialType));
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
        if ("ABNORMAL".equals(result)) {
            String pc = str(body.get("problemCode"));
            if (isBlank(pc)) {
                throw new BizException(422, "「" + name + "」为异常时必须记录隐患判定（S0—S7），不得留空");
            }
            if (!PROBLEM_CODES.contains(pc)) {
                throw new BizException(422, "「" + name + "」隐患码非法：" + pc + "（取值范围 S0—S7）");
            }
        }
        boolean isKey = Boolean.TRUE.equals(item.get("isKey"));
        boolean photoRequired = Boolean.TRUE.equals(item.get("photoRequired"));
        if (isKey && photoRequired && !"NA".equals(result) && !hasPhoto) {
            throw new BizException(422, "关键项「" + name + "」为试验/测试/校验/检测类，必须至少附 1 张照片留证（TSG 注A-2）");
        }
        // 现场照片取证：照片与令牌按下标一一对应，缺令牌即拒绝（fail closed）——
        // 水印的权威值以服务端记录为准，无令牌的 photoUrls 只是本地路径
        List<Map<String, Object>> verifiedPhotos = verifyPhotoEvidence(orderId, itemId, body);
        List<Object> fileIds = asList(body.get("photoFileIds"));
        if (!fileIds.isEmpty() && verifiedPhotos.size() < fileIds.size()) {
            throw new BizException(422, "「" + name + "」现场照片缺少取证令牌（"
                    + verifiedPhotos.size() + "/" + fileIds.size() + " 张已核验），请重新拍照");
        }
        item.put("result", result);
        item.put("value", body.get("value") != null ? dbl(body.get("value")) : null);
        item.put("valueText", strOrEmpty(body.get("valueText")));
        item.put("abnormalDesc", strOrEmpty(body.get("abnormalDesc")));
        item.put("skipReason", strOrEmpty(body.get("skipReason")));
        item.put("problemCode", strOrEmpty(body.get("problemCode")));
        item.put("photos", resolvePhotoUrls(verifiedPhotos));
        item.put("photoFileIds", body.get("photoFileIds") == null ? new ArrayList<>() : body.get("photoFileIds"));
        item.put("photoEvidence", verifiedPhotos);
        // 检查项的权威取证时间：取服务端签发的拍摄时间，而非客户端 recordedAt
        if (!verifiedPhotos.isEmpty()) {
            item.put("recordedAt", verifiedPhotos.get(0).get("shotAt"));
        } else {
            String recordedAt = str(body.get("recordedAt"));
            item.put("recordedAt", recordedAt == null || recordedAt.isEmpty()
                    ? TimeUtil.format(TimeUtil.now()) : recordedAt);
        }
        o.checklistJson = JsonUtil.write(items);
        orderMapper.updateById(o);
        return JsonUtil.map("ok", true, "itemId", itemId);
    }

    /**
     * 由已验签的取证令牌按 fileId 反查照片可访问 URL。
     * 客户端上报的 photoUrls 一律不采信——它会被写入维保记录并在 PDF 导出时由服务器抓取，
     * 等于给出一条任意 URL 的 SSRF 通道（可打云元数据端点）。
     */
    private List<String> resolvePhotoUrls(List<Map<String, Object>> verifiedPhotos) {
        List<String> urls = new ArrayList<>();
        for (Map<String, Object> rec : verifiedPhotos) {
            String url = fileStorageService.resolveUrl(str(rec.get("fileId")));
            if (!isBlank(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
     * 校验现场照片的取证令牌，返回服务端权威的拍摄存证。
     * 令牌与 photoFileIds **按数组下标一一对应**（不用 fileId 匹配），使离线补传在上传照片后
     * 无需回填 fileId 即可通过校验。令牌必须绑定本工单与本检查项，篡改/过期/跨项一律拒绝。
     * 缺令牌的项不写入结果，由调用方按数量校验后fail closed 拒绝。
     */
    private List<Map<String, Object>> verifyPhotoEvidence(String orderId, String itemId,
                                                          Map<String, Object> body) {
        List<Object> fileIds = asList(body.get("photoFileIds"));
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (Object raw : asList(body.get("photoEvidence"))) {
            if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) raw;
                tokens.add(m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < fileIds.size(); i++) {
            String token = i < tokens.size() ? str(tokens.get(i).get("evidenceToken")) : null;
            if (isBlank(token)) {
                continue;
            }
            Map<String, Object> payload = evidenceTokenService.verifyOnly(token, orderId, itemId);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("fileId", String.valueOf(fileIds.get(i)));
            rec.put("shotAt", TimeUtil.format(TimeUtil.fromMillis(numOf(payload.get("st")) * 1000L)));
            rec.put("latitude", EvidenceTokenService.latOf(payload));
            rec.put("longitude", EvidenceTokenService.lngOf(payload));
            rec.put("evidenceNonce", str(payload.get("n")));
            out.add(rec);
        }
        return out;
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
    /**
     * 签退落库（**事务内**）：校验 → 工单置 DONE → 冻结检查项/签名/2.6 报文快照 → 插入维保记录。
     * 不含任何对外调用；平台 2.6 转发见 {@link #reportAfterCheckout}，必须在事务提交后进行。
     */
    @Transactional(rollbackFor = Exception.class)
    public MaintainRecord checkout(String orderId, Map<String, Object> body) {
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
        // 兜底防御：存在异常项却无隐患码的检查项（历史数据/绕过 submitItem 的写入）不得静默报S0
        List<Map<String, Object>> abnormalUncoded = frozen.stream()
                .filter(i -> "ABNORMAL".equals(String.valueOf(i.get("result"))))
                .filter(i -> String.valueOf(i.get("problemCode")).isEmpty())
                .collect(Collectors.toList());
        if (!abnormalUncoded.isEmpty()) {
            throw new BizException(1003, "检查项「" + abnormalUncoded.get(0).get("name")
                    + "」为异常但未记录隐患判定，无法签退");
        }
        if (problemCodes.isEmpty()) {
            problemCodes = List.of("S0"); // 仅当无任何异常项时补 S0 = 未发现严重事故隐患（平台 2.6 约定）
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
        // 签名取证：令牌绑定角色与服务端时间；签名图 URL 一律由服务端按 fileId 反查，
        // 客户端上报的 signatureUrl 一律不采信（否则可把任意地址写入合规记录，
        // 并在 PDF 导出时由服务器去拉取，形成 SSRF）
        boolean hasAssistant = o.assistantName != null && !o.assistantName.isEmpty();
        Map<String, Object> sigEvidence = asMap(body.get("signatureEvidence"));
        verifySignEvidence(orderId, "PRINCIPAL", sigEvidence == null ? null : sigEvidence.get("principal"));
        if (hasAssistant) {
            verifySignEvidence(orderId, "ASSISTANT", sigEvidence == null ? null : sigEvidence.get("assistant"));
        }
        String principalFileId = strOrEmpty(body.get("signatureFileId"));
        if (isBlank(principalFileId)) {
            throw new BizException(422, "缺少主维保人员签名图");
        }
        String principalUrl = fileStorageService.resolveUrl(principalFileId);
        if (isBlank(principalUrl)) {
            throw new BizException(422, "主维保人员签名图上传记录不存在，请重新上传");
        }
        String assistantUrl = "";
        if (hasAssistant) {
            String assistantFileId = strOrEmpty(body.get("assistantSignatureFileId"));
            if (isBlank(assistantFileId)) {
                throw new BizException(422, "缺少配合人员签名图");
            }
            assistantUrl = fileStorageService.resolveUrl(assistantFileId);
            if (isBlank(assistantUrl)) {
                throw new BizException(422, "配合人员签名图上传记录不存在，请重新上传");
            }
        }
        r.workerSignatureUrl = principalUrl;
        r.assistantSignatureUrl = assistantUrl;
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
        return r;
    }

    /**
     * 签退后上报平台 2.6 —— **必须在 {@link #checkout} 的事务提交之后**调用。
     *
     * <p>平台转发是对外 HTTPS 调用（最长 30s 超时）：若放在签退事务内，一旦它异常/超时，
     * 事务回滚会把已写入的维保记录一起撤销，而工单状态可能已被外部观察到，
     * 形成"工单已签退但维保记录不存在"的数据空洞。故签退只负责落库（事务内），
     * 上报在本方法中单独进行；上报失败按 AGENTS §2.3 不自动重试，只标记 FAILED 待人工处理。
     */
    public Map<String, Object> reportAfterCheckout(String orderId, MaintainRecord r) {
        r.reportStatus = reportService.attemptUpload(r);
        recordMapper.updateById(r);
        WorkOrder o = orderMapper.selectById(orderId);
        if (o != null) {
            o.reportStatus = r.reportStatus;
            orderMapper.updateById(o);
        }
        return JsonUtil.map(
                "workOrderId", orderId,
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

    static Double dbl(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
