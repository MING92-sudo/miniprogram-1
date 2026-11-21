package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

/**
 * 工单与现场作业（docs/04 A.2 契约）：
 * 列表筛选/详情/扫码识单/签到（双人动态码）/检查项校验（TSG 注A-1/A-2）/签退
 * （时长下限 30 分钟、关键项照片留证、无隐患填 S0）→ 冻结维保记录 + 2.6 报文快照。
 * 详情与全部现场作业写端点先过 requireOrderScope（V7 班组数据权限，见该方法注释）。
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    /** 业务规则：签到—签退间隔不少于 N 分钟（app.work-duration-minutes 默认 30，业主补充规则，上线前待确认） */
    private static final int DEFAULT_MIN_WORK_DURATION_MINUTES = 30;
    /**
     * 严重事故隐患码 S0—S7（平台 V1.5 规范 3.2，与前端 constants PROBLEM_CODES 同源）。
     * S0「未发现严重事故隐患」是合法取值：检查项异常但不构成严重隐患时填 S0；
     * 只有没有任何异常检查项时签退才自动补 S0。异常项一律要求显式记录判定，
     * 杜绝"有异常却报未发现隐患"的假数据上报监管平台。
     */
    private static final Set<String> PROBLEM_CODES = Set.of(
            "S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7");
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
    private final HandoverOrderMapper handoverOrderMapper;
    private final MessageMapper messageMapper;
    private final ChecklistService checklistService;
    private final PlatformReportService reportService;
    private final EmployeeScopeService scopeService;
    private final CheckinThresholdService thresholdService;
    private final com.cqwlw.maintenance.config.AppProperties props;
    private final FileStorageService fileStorage;
    private final TransactionTemplate transactionTemplate;

    /** M1 生命周期钩子（setter 注入避免改写 8 处测试构造器；生产 Spring 必注入） */
    private RecordLifecycleService recordLifecycleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecordLifecycleService(RecordLifecycleService recordLifecycleService) {
        this.recordLifecycleService = recordLifecycleService;
    }

    /** 租户快照（权限 P2 T4）：可选注入，未注入时快照留空、不阻断签退（保护既有测试构造器） */
    private TenantSnapshotService tenantSnapshotService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTenantSnapshotService(TenantSnapshotService tenantSnapshotService) {
        this.tenantSnapshotService = tenantSnapshotService;
    }

    /**
     * 手机号互斥校验（2.6 备注五角色，用户 2026-11-19 要求前移至"维保作业前"）：
     * 可选注入，未注入时跳过（保护既有 6 处 new WorkOrderService 的测试构造器；生产 Spring 必注入）。
     */
    private PhoneMutexService phoneMutexService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhoneMutexService(PhoneMutexService phoneMutexService) {
        this.phoneMutexService = phoneMutexService;
    }

    /**
     * M12 退回池审计（setter 注入保护既有测试构造器；生产 Spring 必注入）。
     * 与其它审计点「失败吞异常」口径不同：退回池审计是清库前唯一留证，属合规关键证据——
     * 未注入时退回池 fail-closed（500），绝不静默清库丢审计。
     */
    private AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 工单区域名解析（Task 10：管理端工单池「待接单 · 区域名」与小程序池卡片依赖 toMap 透出
     * regionId/regionName）；setter 注入保护既有测试构造器，未注入时 regionName 回空串。
     */
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

    /**
     * 作业门禁：本次工单五角色手机号互斥预校验（1002 + conflicts[]）。
     * 与 buildReportPayload 取数口径一致；未注入校验服务时跳过（兼容旧测试）。
     */
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

    // ── 首页汇总 ──
    // 读接口不触发派单：派单只由定时任务（09:00 + 13 分钟兜底）与管理端显式操作驱动，
    // 否则并发打开首页会造成重复派单，并使读接口偶发失败（审查 B6/B7）
    public Map<String, Object> homeSummary(String empId) {
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
        // 班组数据权限：privileged 返回 null（不限），其余限定本人/本组登记人 + 历史 created_by 为 NULL 的行。
        // 注意：条件块必须在 SQL 里完全缺席，不能写成空的 .and(w -> {})——MyBatis-Plus 仍会拼出「AND ()」，
        // 导致 MySQL 语法错误 1064（管理角色首页 500 的根因）；空集合也不能 .in()，同样生成「IN ()」。
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

    /**
     * 班组数据权限（V7）：组长看本组，组员看本人，管理角色不限；Task 8 起读范围扩到本区域。
     * 区域抢单池（2026-11-20 设计 §4.2）：未认领工单再按 {@link #poolVisible} 池谓词放行。
     */
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

    /**
     * 小程序「工单」角标：数据域内未闭环工单数（PENDING/PROCESSING），与工单列表
     * 同 {@link #scopeOrders} 谓词（池内可接 + 本人/配合在途）。MiniappMenuService 唯一调用方——
     * 旧实现错挂 fault OPEN 计数，角标恒等于开放急修数，与工单页内容无关（2026-11-21 真机反馈）。
     */
    public long countActionableOrders(String empId) {
        List<WorkOrder> open = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING"));
        return scopeOrders(open, empId).size();
    }

    /**
     * 工单归属校验（V7 班组数据权限）：读（详情/清单）与写（签到/检查项/签退）共用同一谓词。
     * 写端点若缺此校验，任一已登录作业人员凭他人工单 id 即可代签到、代填检查项、代签退；
     * 签退会自动转发平台 2.6，等于向监管平台写入虚假维保数据（AGENTS §6 合规底线 → Blocker）。
     * 区域抢单池（M9/M11）：未认领工单按 {@link #poolVisible} 放行同区域持平台ID者；
     * 已认领工单不走池分支，仍按 主/配合人 platformId 命中判断。
     */
    public void requireOrderScope(WorkOrder o, String empId) {
        Employee emp = scopeService.require(empId);
        Set<String> ids = scopeService.visibleWorkerPlatformIds(emp);
        if (ids == null || in(ids, o.workerPlatformId) || in(ids, o.assistantPlatformId)
                || poolVisible(o, emp)) {
            return;
        }
        throw new BizException(1403, "仅可查看或操作本人工单或本班组工单");
    }

    /** null 安全 contains：未认领先兆（workerPlatformId 为 null）不得触发不可变集合 NPE */
    private static boolean in(Set<String> ids, String platformId) {
        return platformId != null && ids.contains(platformId);
    }

    /**
     * 区域抢单池谓词（2026-11-20 设计 M9/M11）：仅未认领（workerPlatformId 空）工单进入池分支——
     * 登录人持平台ID（D4 门禁：无平台ID 不可见/不可认领）且工单不限区域（regionId 空）
     * 或工单 regionId == 登录人 regionId。不按角色再限（M11），管理角色在 ids==null 处已先行放行。
     */
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
        if (!Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90d || lat > 90d || lng < -180d || lng > 180d) {
            throw new BizException(422, "签到定位坐标非法，请重新获取真实位置");
        }
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        // 用户 2026-11-19：作业前门禁——2.6 五角色手机号互斥预校验，置于任何写操作之前
        //（冲突不落库、不消费动态码、不改状态；docs/01 §3.2.4 + docs/04 第⑤级前移）。
        requirePhoneMutexForOrder(o, el);
        // 角色由服务端登录身份复核，不能信任请求体 role；未认领池单仍在围栏通过后原子认领。
        if (assistant) {
            // M5：身份比对必须先于动态码消费——顺序反了错误人选会吞掉正确人选的码
            requireAssignedAssistant(o, empId);
            consumeDynamicCode(o, str(body.get("dynamicCode")));
        } else if (!isBlank(o.workerPlatformId)) {
            requirePrincipal(o, empId == null ? null : scopeService.require(empId));
        }
        // 定位校验（docs/02 §5.4）：电梯坐标缺失 → UNKNOWN，只留痕不拦截（docs/04 A.0.1 码表 1005）
        String geoStatus = "UNKNOWN";
        double distance = -1d;
        // 阈值三级解析：电梯级 → 品种级（sys_param）→ 全局（sys_param → app 配置），docs/02 §5.4
        int threshold = thresholdService.resolve(el);
        if (el != null && el.lat != null && el.lng != null) {
            distance = GeoUtil.distanceMeters(lat, lng, el.lat.doubleValue(), el.lng.doubleValue());
            if (assistant) {
                // D6：配合人免距离围栏——永不 1001 拦截，geoStatus 记 ASSISTANT_EXEMPT；
                // 定位仍必填（M2），算出的距离照常写入留痕（extra.distance）
                geoStatus = "ASSISTANT_EXEMPT";
            } else {
                geoStatus = el.geoStatus == null || el.geoStatus.isEmpty() ? "PROVIDED" : el.geoStatus;
                // D7/D9：自采（SELF_COLLECTED）坐标自回填起即为围栏基准，存量立即生效、不设豁免；
                // 坐标不准致现场 1001 被拦的由管理端人工修正电梯坐标（2026-11-20 区域抢单池设计 §4.3）
                if (distance > threshold) {
                    throw new BizException(1001, "签到位置超出允许范围：距电梯 " + round1(distance)
                            + " 米，阈值 " + threshold + " 米；请到现场后重试");
                }
            }
        }
        // 区域抢单池「签到即认领」（2026-11-20 设计 §4.2 / M1/D4）：主维保签到未认领工单即原子认领。
        // 位置刻意放在围栏校验通过之后、写签到留痕之前——围栏拦截（1001）说明人未到现场，
        // 不得抢走工单；认领前的 422 平台ID门禁同样不产生任何写副作用。
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
            // D8 池化认领打标：签退恒双人门禁据此覆盖不限区域池单（regionId 空的新单），
            // 存量单留痕无此标记，不受影响
            extra.put("claimedViaPool", true);
        }
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
        // 用户需求③：电梯无坐标时，以签到定位自动回填电梯坐标档案（坐标来源=现场采集，docs/02 §5.4）。
        // D9：回填仅限主维保——配合人免围栏（D6），其远距定位不得污染围栏基线
        boolean geoBackfilled = false;
        if (!assistant && el != null && (el.lng == null || el.lat == null)) {
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

    /**
     * 原子认领（M1/D4）：登录人无平台ID → 422「请先在管理端完成平台人员登记」（零写副作用）；
     * 否则经 {@link WorkOrderMapper#claimOrder} 条件更新，命中 0 行即被并发认领——
     * 重查工单回显现认领人姓名后 1003。认领成功后把 worker* 写入内存工单，
     * 由签到主流程的 updateById 与签到留痕同事务语义落库（status→PROCESSING 不变）。
     */
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

    // ── 配合人候选与指派（2026-11-20 区域抢单池设计 D3/D4/D8/M5/M10） ──

    /**
     * 配合人身份比对（M5）：工单已指定配合人（assistantPlatformId 非空）时，签到者必须为该配合人本人，
     * 否则 1003「你未被指定为配合人」——防「码给错人」冒签；必须先于动态码消费，错误人选不得吞码。
     * I2 fail-closed（2026-11-21 最终审查）：池化新单（regionId 非空，或留痕带 claimedViaPool 打标，
     * 与 checkout pooledOrder 判定同一口径）尚未指定配合人时，任何人不得以 ASSISTANT 签到——
     * 否则吞码 + 匿名留痕把工单锁死（签退 D8 422、assignPartner 1003、claimOrder 因非 PENDING 落败）。
     * M8 兼容分支：存量工单（regionId 空且无认领标记）assistantPlatformId 为空时跳过比对，维持原动态码口径。
     */
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

    /**
     * 配合人候选（M10：与池可见性同用工单 regionId 快照，电梯事后改区域不影响在池工单）：
     * 候选 = 同区域快照（快照空=不限区域 → 全部）∧ 持平台ID（D4）∧ 在职 ∧ 排除主维保本人。
     * 仅主维保本人可调用（1403）。
     */
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

    /**
     * 指派/改选配合人（D3/D8）：仅主维保本人（1403）；PENDING/PROCESSING 且配合人未签到
     * （assistantCheckinExtra 空）才可改选；候选人服务端复核与 partner-candidates 同一谓词
     * （不信任前端列表），不符 422。
     */
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
        o.assistantName = cand.name;
        o.assistantPlatformId = cand.platformId;
        orderMapper.updateById(o);
        return JsonUtil.map("ok", true, "assistantName", nz(cand.name), "assistantPlatformId", cand.platformId);
    }

    /** 仅主维保本人：登录人 platformId == 工单 workerPlatformId（认领后 worker* 指向认领人） */
    private void requirePrincipal(WorkOrder o, Employee login) {
        if (login == null || isBlank(login.platformId) || !login.platformId.equals(o.workerPlatformId)) {
            throw new BizException(1403, "仅主维保本人可执行此操作");
        }
    }

    /** 配合人候选谓词（M10/D4）：同区域快照（空=不限）∧ 持平台ID ∧ 在职 ∧ 排除主维保本人 */
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

    // ── 管理端「退回池」（2026-11-20 区域抢单池设计 §4.4 / D10/M12） ──

    /**
     * 把被错领或认领人无法继续的工单清回区域池。
     * ①DONE → 422（维保记录已生成并可能已上报监管平台，清回池会造成平台数据与本地不一致）；
     * ②未认领 PENDING → 幂等 ok，不改状态、不写审计、不动库；
     * ③已认领/已有签到留痕 → M12 审计先行：先把「操作人 + 原认领人/原配合人 + 签到留痕快照」
     * 写入 sys_audit_log（写失败即抛异常回滚事务，绝不先清库后丢审计——本场景审计是清库的
     * 唯一留证，不采用其它审计点「失败吞异常」口径），再在同一事务内清空 worker 系/assistant 系/
     * 签到留痕/签名/动态码并回 PENDING；regionId 快照不变（M10，电梯事后改区域不影响在池工单）。
     * 签名必须随认领人一并清除：不清则新认领人签退时会复用旧签名 fileId 冻结进 2.6 报文（虚假签名）。
     */
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
            // fail-closed：审计不可用时禁止清库（M12 合规关键证据）
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
                // 读-改竞态守卫（Fix round 1）：工人签退事务在「读 PROCESSING」与清库之间提交，
                // status 已 DONE——条件更新命中 0 行即竞态落败。此处抛异常回滚整个事务
                //（审计一并回滚），绝不能覆写 DONE 回 PENDING：否则抹掉签到/签名留痕，
                // 且 originalRecordId/MaintainRecord/2.6 上报已存在，工单重入池被二次认领
                // 再签退 → 同一工单向监管平台产生第二条记录（AGENTS §6 合规底线）。
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

    /**
     * 清库条件更新（updateById 默认跳过 null 字段，清库必须显式 SET NULL，
     * 同 AdminArchiveService 电梯 clearRegion 口径）；regionId/planTime/checklist 不动。
     * WHERE 带 {@code status <> 'DONE'} 状态守卫（claimOrder/consumeDynamicCodeIfUnused 同一
     * 并发防护范式）：读时 422 检查挡不住读-改竞态，命中 0 行由调用方判竞态落败回滚。
     */
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

    // ── 双人动态码（docs/03 §3.2 项6：60 秒有效、前端每 5 秒刷新倒计时；绑定工单、一次性使用） ──

    /**
     * 主维保生成/刷新动态码（docs/03 §3.2 项6：每 5 秒刷新、60 秒有效）。
     * 码由 HMAC(密钥, 工单ID|时间步长) 派生：绑定工单、无需落库即可轮换，密钥取 app.jwt-secret（不打印）。
     */
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

    /**
     * 校验并消费（一次性）：先按 60 秒窗口校验，再以原子条件更新落库——
     * {@code UPDATE ... WHERE dynamic_code IS NULL OR = ''}，命中 0 行即已被并发消费。
     * 旧实现「内存判空 + updateById」存在 TOCTOU：两个并发签到者同时读到空码、同时校验通过、
     * 各自 updateById 覆盖，同一动态码被两次使用。原子更新把「判空 + 写入」压成单条 SQL，
     * 并发下只有一条命中；命中 0 行（被对端抢注）→ 1003。
     */
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

    /**
     * D8 池化认领标记（2026-11-20 区域抢单池）：主维保签到认领时在 checkinExtraJson 写入
     * claimedViaPool=true，签退恒双人门禁据此把「不限区域（regionId 空）的池化新单」也纳入门禁。
     * 存量工单留痕无此字段 → false，维持旧口径（M8）。
     */
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
        // M13 已知取舍：GET 详情路径会在此懒生成并 updateById 清单（读路径写库）。
        // 功能正确、移动生成时机涉及派单/模板链路回归，风险大于收益，保留现状。
        if (o.checklistJson == null || o.checklistJson.isEmpty()) {
            Elevator el = elevatorMapper.selectById(o.elevatorId);
            String category = el == null ? "" : el.category;
            String specialType = el == null || el.specialType == null ? "" : el.specialType;
            o.checklistJson = JsonUtil.write(checklistService.buildChecklist(o.workTypeCode, category, specialType));
            orderMapper.updateById(o);
        }
        List<Map<String, Object>> items = JsonUtil.readList(o.checklistJson);
        // 读路径重签：清单照片 URL 带时效，工单详情/作业页可能远晚于上传时间打开。
        // 照片以 photoFileIds 解析出的可访问 URL 为准；历史/演示数据的本地临时路径（wxfile://、http://tmp/）不入回显，
        // 否则照片只在拍照设备可见、其他设备/管理端乱图（2026-11-06 根因：photos 曾落本地临时路径）
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

    public Map<String, Object> getChecklist(String orderId, String empId) {
        WorkOrder o = findOr404(orderId);
        requireOrderScope(o, empId);
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
        // 照片落档以 photoFileIds 解析出的可访问 URL 为准，不落本地临时路径（wxfile://、http://tmp/），
        // 否则照片只在拍照设备可见、其他设备/管理端乱图（2026-11-06 根因）
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

    // ── 签字（各自手机签，docs/01 §3.7.5：主维保和配合人员分别手写签名） ──

    /**
     * 主维保/配合人员各自在自己手机签字（docs/01 §3.7.5）。
     * 严格各签各的：普通作业人员仅可签本人对应角色，组长/管理员可代签（{@link #requireSignRole}）；
     * 仅 PROCESSING 状态可签，签退（仅主维保）前由 checkout 校验双签名齐全。
     */
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
        String fileId = str(body.get("signatureFileId")).trim();
        if (fileId.isEmpty()) {
            throw new BizException(422, "签字图未上传，请重试");
        }
        LocalDateTime now = TimeUtil.now();
        if (assistant) {
            o.assistantSignatureFileId = fileId;
            o.assistantSignatureAt = now;
        } else {
            o.workerSignatureFileId = fileId;
            o.workerSignatureAt = now;
        }
        orderMapper.updateById(o);
        return JsonUtil.map("ok", true, "role", assistant ? "ASSISTANT" : "PRINCIPAL");
    }

    /**
     * 签字身份校验（严格各签各的，防代签）：普通作业人员仅可签本人对应角色；
     * ADMIN/SYS_ADMIN/LEADER 可代签（LEADER 对本组负责，班组数据权限同源，docs/01 §3.7.5）。
     */
    private void requireSignRole(WorkOrder o, String role, String empId) {
        Employee login = scopeService.require(empId);
        String r = login.role == null ? "" : login.role;
        if ("ADMIN".equals(r) || "SYS_ADMIN".equals(r) || "LEADER".equals(r)) {
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
        // 仅主维保可签退（docs/01 §3.7.5）：普通作业人员只有主维保本人可签退；
        // 组长/管理员可代签退（处理异常工单），配合人员不可签退（防代签退 → 平台 2.6 虚假数据）
        Employee login = scopeService.require(empId);
        String loginRole = login.role == null ? "" : login.role;
        boolean privileged = "ADMIN".equals(loginRole) || "SYS_ADMIN".equals(loginRole) || "LEADER".equals(loginRole);
        if (!privileged) {
            String pid = login.platformId;
            if (pid == null || pid.isBlank() || !pid.equals(o.workerPlatformId)) {
                throw new BizException(1403, "仅主维保可签退");
            }
        }
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(1003, "请先完成签到");
        }
        // ── D8 签退恒双人门禁（2026-11-20 区域抢单池设计 §4.2，平台 2.6 workMan2Id 必填）──
        // 适用范围（池化新单，双信号或运算）：①V57 起派单落 regionId 快照非空；②或主维保签到认领
        // 打标 claimedViaPool=true（覆盖不限区域电梯的池单——regionId 空但确属新单）。
        // 存量在途工单（regionId 空且留痕无认领标记，M8，含派单时代 assistantName="无" 的
        // 单人豁免路径）不触发本门禁，维持旧口径跑完。
        // 门禁①②置于检查项/时长校验与任何写操作之前；门禁③（双签）在下方签名校验处恒触发。
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

        // 签字来源：优先取工单各自手机签（V23 字段），兼容旧客户端直接在签退体提交（docs/01 §3.7.5）
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
        // D8：池化新单双签恒必填（不再存在单人工单）；存量工单（regionId 空且无认领标记）
        // 维持"有配合人时必填"旧口径（M8）。本门禁同样先于全部业务状态写。
        if ((pooledOrder || hasAssistant) && assistantSignature.isEmpty()) {
            throw new BizException(422, "请完成配合人员签名（双人签字）");
        }

        // 设备信息档案门禁（用户 2026-11-19）：电梯编码/设备代码/注册代码/单位内编号/出厂编号
        // 为上传维保记录（平台 2.6）的必传项，缺任一项不得生成记录、不得上报
        Elevator el = elevatorMapper.selectById(o.elevatorId);
        if (el == null) {
            throw new BizException(1404, "电梯档案不存在，无法上传维保记录");
        }
        DeviceProfileValidator.requireForRecord(el);
        // 用户 2026-11-19：签退冻结快照前复核五角色互斥（docs/04 第⑤级），
        // 拦截在写状态之前——覆盖"签到后档案被改动"导致的漏网冲突，与 2.6 取数同源。
        requirePhoneMutexForOrder(o, el);

        o.status = "DONE";
        o.checkoutTime = checkoutTime;
        o.originalRecordId = Ids.nextRecordId();
        o.reportStatus = "SUBMITTED";
        o.duration = TimeUtil.formatDuration(TimeUtil.toMillis(checkoutTime) - TimeUtil.toMillis(o.checkinTime));

        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
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
        // 缺省安全防护勾选时写 SQL NULL，不要写成字符串 "null"：
        // Jackson 读 "null" 得到 null 对象，PDF 渲染 safetyLine 会 NPE（E2E 实测客户签字 500）
        r.safetyJson = body.get("safetyConfirm") == null ? null : JsonUtil.write(body.get("safetyConfirm"));
        r.todoDesc = strOrEmpty(body.get("todoDesc"));
        r.workerSignatureUrl = workerSignature;
        r.assistantSignatureUrl = assistantSignature;
        r.problemCodesJson = JsonUtil.write(problemCodes);
        r.originalRecordId = o.originalRecordId;
        r.reportStatus = o.reportStatus;
        r.retryCount = 0;
        r.nextMaintenanceDate = LocalDate.now(TimeUtil.ZONE).plusDays(interval);
        r.confirmStatus = "PENDING";
        // M1：记录生命周期初始化（复核开关分流）+ 来源工单关联（复核退回回写用）
        r.workOrderId = o.id;
        if (tenantSnapshotService != null) tenantSnapshotService.applyRecord(r, o, el);
        r.lifecycleStatus = recordLifecycleService == null ? null : recordLifecycleService.initialStatus();
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
        // 首保签退回写遗留问题（Task 5 后半）：来源移交单 + handoverIssue=true → 标记移交单并通知安装部。
        // 放主事务之后、平台上报之前；回写失败不阻断签退合规链（内部防御 try/catch）。
        if (o.sourceHandoverId != null && Boolean.TRUE.equals(o.handoverIssue)) {
            writeBackHandoverIssue(o);
        }
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
     * 首保签退回写来源移交单遗留问题标记 + 消息中心通知安装部（Task 5 后半）。
     * 防御：来源移交单查不到只记日志跳过，任何异常都不抛出——回写失败不得回滚签退合规链。
     */
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("orderNo", o.orderNo);
        m.put("elevatorId", o.elevatorId);
        m.put("workType", o.workType);
        m.put("workTypeCode", o.workTypeCode);
        m.put("planTime", TimeUtil.format(o.planTime));
        m.put("status", o.status);
        // 区域抢单池快照（M4）：管理端工单池「待接单 · 区域名」与小程序池段共用；
        // 快照不回溯——电梯事后改区域不影响在池工单（M10）
        m.put("regionId", nz(o.regionId));
        m.put("regionName", regionNameOf(o.regionId));
        m.put("workerName", o.workerName);
        m.put("assistantName", o.assistantName);
        m.put("workerPlatformId", o.workerPlatformId);
        m.put("assistantPlatformId", o.assistantPlatformId);
        m.put("checkinTime", TimeUtil.format(o.checkinTime));
        // 双人分别签到（docs/01 §3.7.2）：前端据此决定"签到/已签到"入口与动态码模式
        m.put("principalCheckedIn", hasCheckin(o.checkinExtraJson));
        m.put("assistantCheckedIn", hasCheckin(o.assistantCheckinExtraJson));
        // 签字各自手机签（docs/01 §3.7.5）：前端据此展示签字入口与已签状态
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
        // 电梯级签到阈值（米）；空=前端按全局默认展示（docs/02 §5.4）
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
