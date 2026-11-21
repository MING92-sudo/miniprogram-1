package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Drill;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.Knowledge;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.Rescue;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.cqwlw.maintenance.util.JsonUtil.map;

/**
 * 台账与目录类业务（docs/04 A.3/A.6/A.7）：
 * 困人救援（节点耗时自动计算，超 30 分钟标记超时且不可删除）、故障闭环、
 * 应急演练（半年覆盖检查）、自行检查（年检预警）、消息、知识库。
 */
@Service
public class DirectoryService {

    private final RescueMapper rescueMapper;
    private final FaultMapper faultMapper;
    private final DrillMapper drillMapper;
    private final InspectRecordMapper inspectMapper;
    private final MessageMapper messageMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ElevatorMapper elevatorMapper;
    private final ChecklistService checklistService;
    private final WorkOrderService workOrderService;
    private final EmployeeScopeService scopeService;
    private final EmployeeMapper employeeMapper;
    private final UseUnitMapper useUnitMapper;
    private final WorkOrderMapper workOrderMapper;
    private final CompanyMapper companyMapper;
    private final FileStorageService fileStorageService;
    private final TenantSnapshotService tenantSnapshotService;

    public DirectoryService(RescueMapper rescueMapper, FaultMapper faultMapper,
                            DrillMapper drillMapper, InspectRecordMapper inspectMapper,
                            MessageMapper messageMapper, KnowledgeMapper knowledgeMapper,
                            ElevatorMapper elevatorMapper, ChecklistService checklistService,
                            WorkOrderService workOrderService, EmployeeScopeService scopeService,
                            EmployeeMapper employeeMapper, UseUnitMapper useUnitMapper,
                            WorkOrderMapper workOrderMapper, CompanyMapper companyMapper,
                            FileStorageService fileStorageService,
                            TenantSnapshotService tenantSnapshotService) {
        this.rescueMapper = rescueMapper;
        this.faultMapper = faultMapper;
        this.drillMapper = drillMapper;
        this.inspectMapper = inspectMapper;
        this.messageMapper = messageMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.elevatorMapper = elevatorMapper;
        this.checklistService = checklistService;
        this.workOrderService = workOrderService;
        this.scopeService = scopeService;
        this.employeeMapper = employeeMapper;
        this.useUnitMapper = useUnitMapper;
        this.workOrderMapper = workOrderMapper;
        this.companyMapper = companyMapper;
        this.fileStorageService = fileStorageService;
        this.tenantSnapshotService = tenantSnapshotService;
    }

    // ── 救援 ──
    public Map<String, Object> createRescue(Map<String, Object> body) {
        String alarmAt = str(body.get("alarmAt"));
        String arriveAt = str(body.get("arriveAt"));
        if (WorkOrderService.isBlank(strOrEmpty(body.get("elevatorCode")))) {
            throw new BizException(422, "请选择电梯");
        }
        if (WorkOrderService.isBlank(alarmAt)) {
            throw new BizException(422, "请填写接警时间");
        }
        if (WorkOrderService.isBlank(arriveAt)) {
            throw new BizException(422, "请填写抵达时间（30 分钟法定红线需据此计算）");
        }
        String rescuedAt = str(body.get("rescuedAt"));
        Integer arriveMin = alarmAt != null && !alarmAt.isEmpty() && arriveAt != null && !arriveAt.isEmpty()
                ? (int) TimeUtil.minutesBetween(alarmAt, arriveAt) : null;
        Integer rescuedMin = alarmAt != null && !alarmAt.isEmpty() && rescuedAt != null && !rescuedAt.isEmpty()
                ? (int) TimeUtil.minutesBetween(alarmAt, rescuedAt) : null;
        boolean overtime = arriveMin != null && arriveMin > 30; // 直辖市法定时限 30 分钟
        Rescue r = new Rescue();
        r.id = Ids.next("rs");
        r.elevatorCode = strOrEmpty(body.get("elevatorCode"));
        r.trappedCount = body.get("trappedCount") == null ? 0 : ((Number) body.get("trappedCount")).intValue();
        r.descr = strOrEmpty(body.get("desc"));
        r.alarmAt = TimeUtil.parse(alarmAt);
        r.departAt = TimeUtil.parse(str(body.get("departAt")));
        r.arriveAt = TimeUtil.parse(arriveAt);
        r.rescuedAt = TimeUtil.parse(rescuedAt);
        r.arriveMinutes = arriveMin;
        r.rescuedMinutes = rescuedMin;
        r.overtime = overtime;
        r.reason = strOrEmpty(body.get("reason"));
        r.action = strOrEmpty(body.get("action"));
        r.status = rescuedAt != null && !rescuedAt.isEmpty() ? "已解除" : "处理中";
        r.createdAt = TimeUtil.now();
        rescueMapper.insert(r);
 return rescueView(r);
    }

    public Map<String, Object> rescueView(Rescue r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorCode", r.elevatorCode);
        m.put("trappedCount", r.trappedCount);
        m.put("desc", r.descr);
        m.put("alarmAt", TimeUtil.format(r.alarmAt));
        m.put("departAt", TimeUtil.format(r.departAt));
        m.put("arriveAt", TimeUtil.format(r.arriveAt));
        m.put("rescuedAt", TimeUtil.format(r.rescuedAt));
        m.put("arriveMinutes", r.arriveMinutes);
        m.put("rescuedMinutes", r.rescuedMinutes);
        m.put("overtime", r.overtime);
        m.put("reason", r.reason);
        m.put("action", r.action);
        m.put("status", r.status);
        m.put("createdAt", TimeUtil.format(r.createdAt));
        return m;
    }

    public List<Map<String, Object>> listRescues() {
        return rescueMapper.selectList(new LambdaQueryWrapper<Rescue>().orderByDesc(Rescue::getCreatedAt))
                .stream().map(this::rescueView).toList();
    }

    public Map<String, Object> getRescue(String id) {
        Rescue r = rescueMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "救援记录不存在");
        }
        return rescueView(r);
    }

    // ── 故障 ──
    public Map<String, Object> createFault(Map<String, Object> body, String empId) {
        if (WorkOrderService.isBlank(strOrEmpty(body.get("elevatorCode")))) {
            throw new BizException(422, "请选择电梯");
        }
        if (WorkOrderService.isBlank(strOrEmpty(body.get("desc")))) {
            throw new BizException(422, "请填写故障描述");
        }
        Fault f = new Fault();
        f.id = Ids.next("ft");
        f.createdBy = empId;
        f.elevatorCode = strOrEmpty(body.get("elevatorCode"));
        // 故障等级：一般故障 / 困人（紧急）（小程序登记页选择，V2.28）
        f.faultType = strOrEmpty(body.get("faultType")).isEmpty() ? "一般故障" : strOrEmpty(body.get("faultType"));
        // 困人人数（C-18/V62）：0-99；缺省 0=非困人急修
        Object tc = body.get("trappedCount");
        int trapped = tc == null ? 0 : ((Number) tc).intValue();
        if (trapped < 0 || trapped > 99) {
            throw new BizException(422, "困人人数须在 0-99 之间");
        }
        f.trappedCount = trapped;
        f.descr = strOrEmpty(body.get("desc"));
        f.siteDesc = strOrEmpty(body.get("siteDesc"));
        f.photos = JsonUtil.write(body.get("photos") == null ? List.of() : body.get("photos"));
        f.status = "OPEN";
        f.handleDesc = "";
        f.createdAt = TimeUtil.now();
        f.faultNo = nextFaultNo();
        // 到场时间以维保人员当日该梯首次签到为准；无签到记录时以报修时间兜底
        f.arrivedAt = firstCheckinToday(f.elevatorCode);
        if (f.arrivedAt == null) {
            f.arrivedAt = f.createdAt;
        }
        // 租户快照（权限 P2 T4）：按电梯编码回填使用单位；查不到时两列留 NULL（Runner 兜底）
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, f.elevatorCode).last("LIMIT 1"));
        if (el != null) {
            tenantSnapshotService.applyFault(f, el);
        }
        faultMapper.insert(f);
        return faultView(f);
    }

    /** 急修单派单（管理端调度）：指派接单维保员，OPEN → ASSIGNED（docs/04 V2.28 派单系统） */
    public Map<String, Object> dispatchFault(String id, String workerId) {
        Fault f = faultMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "急修单不存在");
        }
        if (!"OPEN".equals(f.status)) {
            throw new BizException(422, "仅待派单的急修单可派单");
        }
        Employee worker = employeeMapper.selectById(workerId);
        if (worker == null) {
            throw new BizException(1404, "维保人员不存在");
        }
        f.dispatchWorkerId = workerId;
        f.dispatchedAt = TimeUtil.now();
        f.status = "ASSIGNED";
        faultMapper.updateById(f);
        Map<String, Object> view = faultView(f);
        enrichFaultViews(List.of(view));
        return view;
    }

    /** M1 急修七态：抢单（本区域校验）OPEN→ASSIGNED */
    public Map<String, Object> grabFault(String id, String empId) {
        Fault f = faultOr404(id);
        if (!"OPEN".equals(f.status)) {
            throw new BizException(422, "仅待接单的急修单可抢单");
        }
        Employee me = employeeMapper.selectById(empId);
        if (me == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, f.elevatorCode).last("LIMIT 1"));
        if (me.regionId != null && el != null && el.regionId != null && !me.regionId.equals(el.regionId)) {
            throw new BizException(1403, "超出本区域范围，请联系主管派单");
        }
        f.dispatchWorkerId = empId;
        f.dispatchedAt = TimeUtil.now();
        f.status = "ASSIGNED";
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1：到场打卡（GPS 必填）ASSIGNED→PROCESSING；显式打卡优先于签到推导 */
    public Map<String, Object> arriveFault(String id, String empId, Double lat, Double lng) {
        Fault f = faultOr404(id);
        if (!"ASSIGNED".equals(f.status)) {
            throw new BizException(422, "仅已接单的急修单可到场打卡");
        }
        if (!empId.equals(f.dispatchWorkerId)) {
            throw new BizException(1403, "仅被指派维保人员可打卡");
        }
        if (lat == null || lng == null) {
            throw new BizException(422, "定位坐标必填");
        }
        f.arrivedAt = TimeUtil.now();
        f.status = "PROCESSING";
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1：提交急修单（故障原因/处理措施必填）PROCESSING→PENDING_CONFIRM */
    public Map<String, Object> submitFault(String id, String empId, Map<String, Object> body) {
        Fault f = faultOr404(id);
        if (!"PROCESSING".equals(f.status)) {
            throw new BizException(422, "仅处置中的急修单可提交");
        }
        if (!empId.equals(f.dispatchWorkerId)) {
            throw new BizException(1403, "仅被指派维保人员可提交");
        }
        String reason = trimmed(body.get("faultReason"));
        String handle = trimmed(body.get("handleDesc"));
        if (reason.isEmpty() || handle.isEmpty()) {
            throw new BizException(422, "故障原因与处理措施必填");
        }
        f.faultReason = reason;
        f.handleDesc = handle;
        f.finishedAt = TimeUtil.now();
        f.status = "PENDING_CONFIRM";
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1：使用单位确认（CLIENT_SIGNER，管理员须兼任）PENDING_CONFIRM→CLOSED/REFUSED */
    public Map<String, Object> confirmFault(String id, java.util.Set<String> roles, String unitId,
                                            boolean pass, String reason) {
        Fault f = faultOr404(id);
        if (!"PENDING_CONFIRM".equals(f.status)) {
            throw new BizException(422, "仅待使用单位确认的急修单可确认");
        }
        if (!roles.contains("CLIENT_SIGNER")) {
            throw new BizException(1403, "需使用单位签字人角色");
        }
        checkUnit(f, unitId);
        if (pass) {
            f.status = "CLOSED";
            f.confirmedAt = TimeUtil.now();
        } else {
            if (reason.trim().isEmpty()) {
                throw new BizException(422, "拒绝原因必填");
            }
            f.status = "REFUSED";
            f.refuseReason = reason;
        }
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1：取消（接单前单位管理员/接单后主管；原因必填） */
    public Map<String, Object> cancelFault(String id, java.util.Set<String> roles, String reason) {
        Fault f = faultOr404(id);
        if (reason.trim().isEmpty()) {
            throw new BizException(422, "取消原因必填");
        }
        if ("CLOSED".equals(f.status) || "CANCELLED".equals(f.status)) {
            throw new BizException(422, "已闭环/已取消的急修单不可取消");
        }
        boolean beforeAccept = "OPEN".equals(f.status);
        if (beforeAccept ? !roles.contains("CLIENT_ADMIN") : !roles.contains("MAINT_SUP")) {
            throw new BizException(1403, beforeAccept ? "接单前仅使用单位管理员可取消" : "接单后仅维保主管可取消");
        }
        f.status = "CANCELLED";
        f.cancelReason = reason;
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1：服务评价（仅已闭环、1-5 星、一次性） */
    public Map<String, Object> evaluate(String id, java.util.Set<String> roles, String unitId,
                                        Map<String, Object> body) {
        Fault f = faultOr404(id);
        if (!"CLOSED".equals(f.status)) {
            throw new BizException(422, "仅已闭环的急修单可评价");
        }
        if (roles.stream().noneMatch(r -> r.startsWith("CLIENT"))) {
            throw new BizException(1403, "仅使用单位可评价");
        }
        checkUnit(f, unitId);
        if (f.evaluatedAt != null) {
            throw new BizException(422, "该急修单已评价");
        }
        int score = body.get("score") instanceof Number n ? n.intValue() : 0;
        if (score < 1 || score > 5) {
            throw new BizException(422, "评分须为 1-5 星");
        }
        f.evaluateScore = score;
        f.evaluateText = trimmed(body.get("text"));
        f.evaluatedAt = TimeUtil.now();
        faultMapper.updateById(f);
        return faultView(f);
    }

    /** M1 SLA 升级（系统自动）：超时未闭环标记 escalatedAt 留痕；真实推送依赖 C-01 订阅消息 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 780000)
    public void scanEscalation() {
        for (Fault f : faultMapper.selectList(new LambdaQueryWrapper<Fault>()
                .isNotNull(Fault::getDeadline).isNull(Fault::getEscalatedAt)
                .notIn(Fault::getStatus, List.of("CLOSED", "CANCELLED")))) {
            if (f.deadline.isBefore(TimeUtil.now())) {
                f.escalatedAt = TimeUtil.now();
                faultMapper.updateById(f);
                org.slf4j.LoggerFactory.getLogger(DirectoryService.class)
                        .warn("急修单 SLA 超时升级: id={}", f.id);
            }
        }
    }

    private void checkUnit(Fault f, String unitId) {
        if (unitId == null) {
            return;
        }
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, f.elevatorCode).last("LIMIT 1"));
        if (el == null || !unitId.equals(el.useUnitId)) {
            throw new BizException(1403, "仅本单位急修单可操作");
        }
    }

    private Fault faultOr404(String id) {
        Fault f = faultMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "急修单不存在");
        }
        return f;
    }

    private static String trimmed(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 急修单编号：BWJX + yyyyMMddHHmm + 当日 3 位顺序 = 19 位 */
    private String nextFaultNo() {
        String prefix = "BWJX" + TimeUtil.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        long count = faultMapper.selectCount(new LambdaQueryWrapper<Fault>()
                .likeRight(Fault::getFaultNo, prefix));
        return prefix + String.format("%03d", count + 1);
    }

    /** 到场时间：当日该梯最早一次维保签到（docs/04 A.3 急修单到场以签到为准） */
    private LocalDateTime firstCheckinToday(String elevatorCode) {
        Elevator el = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, elevatorCode)).stream().findFirst().orElse(null);
        if (el == null) {
            return null;
        }
        String today = TimeUtil.date(TimeUtil.now());
        return workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getElevatorId, el.id)
                        .isNotNull(WorkOrder::getCheckinTime)
                        .orderByAsc(WorkOrder::getCheckinTime)).stream()
                .filter(o -> o.checkinTime != null && TimeUtil.date(o.checkinTime).equals(today))
                .map(o -> o.checkinTime).findFirst().orElse(null);
    }

    public Map<String, Object> faultView(Fault f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id);
        m.put("faultNo", f.faultNo == null || f.faultNo.isBlank() ? f.id : f.faultNo);
        m.put("createdBy", f.createdBy == null ? "" : f.createdBy);
        m.put("elevatorCode", f.elevatorCode);
        m.put("faultType", f.faultType);
        m.put("trappedCount", f.trappedCount == null ? 0 : f.trappedCount);
        m.put("desc", f.descr);
        m.put("siteDesc", f.siteDesc == null ? "" : f.siteDesc);
        m.put("arrivedAt", TimeUtil.format(f.arrivedAt));
        m.put("dispatchedAt", TimeUtil.format(f.dispatchedAt));
        m.put("finishedAt", TimeUtil.format(f.finishedAt));
        m.put("todoDesc", f.todoDesc == null ? "" : f.todoDesc);
        m.put("photos", JsonUtil.readStringList(f.photos).stream()
                .map(fileStorageService::resolveStoredUrl).collect(Collectors.toList()));
        m.put("status", f.status);
        m.put("result", f.handleDesc);
        m.put("signature", fileStorageService.resolveStoredUrl(f.signature));
        m.put("confirmedAt", f.confirmedAt == null ? "" : TimeUtil.format(f.confirmedAt));
        m.put("createdAt", TimeUtil.format(f.createdAt));
        return m;
    }

    public Map<String, Object> listFaults(Map<String, String> query, String empId) {
        List<Fault> list = faultMapper.selectList(new LambdaQueryWrapper<Fault>()
                .orderByDesc(Fault::getCreatedAt));
        Set<String> creatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
        if (creatorIds != null) {
            // 历史数据 createdBy 为 NULL：对全员可见（V7 前-only 数据）
            // OPEN 单 = 区域抢单池语义（grab 谓词只查区域），不属任何人的既有工作量——
            // 若按创建人收敛，客户报修（createdBy=unit:*）在维保端列表永不可见、无人能接单（2026-11-21 通道修复）；
            // 已指派给本人的单同样必须可见，否则接单后从本人列表消失、无法到场/提交。
            list = list.stream().filter(f -> f.createdBy == null || creatorIds.contains(f.createdBy)
                    || "OPEN".equals(f.status) || empId.equals(f.dispatchWorkerId)).toList();
        }
        String status = query.get("status");
        if (status != null && !status.isEmpty()) {
            list = list.stream().filter(f -> status.equals(f.status)).toList();
        }
        // 困人急修过滤（C-18/V62，工作台「急修待接」KPI 下钻口径）：trapped=1 → 仅困人单；trapped=0 → 仅非困人单
        if ("1".equals(query.get("trapped"))) {
            list = list.stream().filter(f -> f.trappedCount != null && f.trappedCount > 0).toList();
        } else if ("0".equals(query.get("trapped"))) {
            list = list.stream().filter(f -> f.trappedCount == null || f.trappedCount <= 0).toList();
        }
        List<Map<String, Object>> views = list.stream().map(this::faultView).toList();
        enrichFaultViews(views);
        return workOrderService.paginate(views, query);
    }

    /** 急修单视图增强：登记人姓名 + 电梯名称 + 使用单位名（一梯一档预览/PDF 用） */
    private void enrichFaultViews(List<Map<String, Object>> views) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> phones = new LinkedHashMap<>();
        for (Employee e : employeeMapper.selectList(null)) {
            names.put(e.id, e.name);
            phones.put(e.id, e.phone == null ? "" : e.phone);
        }
        Map<String, Elevator> elevators = new LinkedHashMap<>();
        for (Elevator el : elevatorMapper.selectList(null)) {
            elevators.put(el.elevatorCode, el);
        }
        for (Map<String, Object> v : views) {
            String by = String.valueOf(v.get("createdBy"));
            if (by.startsWith(UnitPortalService.REPORTER_PREFIX)) {
                // 使用单位门户报修（Task 10）：登记人是 use_unit，不是员工
                String unitId = by.substring(UnitPortalService.REPORTER_PREFIX.length());
                v.put("createdByName", "使用单位报修（" + useUnitName(unitId) + "）");
                v.put("reporterPhone", "");
            } else {
                v.put("createdByName", by.isEmpty() ? "" : names.getOrDefault(by, by));
                v.put("reporterPhone", by.isEmpty() ? "" : phones.getOrDefault(by, ""));
            }
            // 派单信息（V20 派单系统）
            String dispatchId = String.valueOf(v.get("dispatchWorkerId"));
            v.put("dispatchedWorkerName", "null".equals(dispatchId) || dispatchId.isEmpty()
                    ? "" : names.getOrDefault(dispatchId, dispatchId));
            // 维保单位：系统默认本单位（company 表首条）
            Company comp = companyMapper.selectList(null).stream().findFirst().orElse(null);
            v.put("companyName", comp == null || comp.name == null ? "" : comp.name);
            Elevator el = elevators.get(String.valueOf(v.get("elevatorCode")));
            v.put("elevatorName", el == null || el.elevatorName == null ? "" : el.elevatorName);
            v.put("useUnitName", el == null || el.useUnitId == null ? "" : useUnitName(el.useUnitId));
            // PDF 电梯基本信息栏（样单：扫码自动带出，不可手改）
            v.put("regCode", el == null || el.regCode == null ? "" : el.regCode);
            v.put("model", el == null || el.model == null ? "" : el.model);
            v.put("location", el == null || el.location == null ? "" : el.location);
            v.put("stationsDoors", el == null || el.stationsDoors == null ? "" : el.stationsDoors);
            v.put("ratedSpec", el == null ? "" : ratedSpec(el));
            // 急修记录单「使用单位与设备基本情况」区块（docs/13 §3.2、§4.2）：
            // 与维保记录 PDF 同源同口径，缺档案时留空由导出层打印占位
            v.put("insideNumber", el == null || el.insideNumber == null ? "" : el.insideNumber);
            v.put("projectName", el == null || el.projectName == null ? "" : el.projectName);
            v.put("category", el == null || el.category == null ? "" : el.category);
            v.put("specialType", el == null || el.specialType == null ? "" : el.specialType);
            // 出厂编号（V59 彻底统一，无 productNo 旧键）
            v.put("factoryNumber", el == null || el.factoryNumber == null ? "" : el.factoryNumber);
            v.put("deviceCode", el == null || el.deviceCode == null ? "" : el.deviceCode);
            v.put("manufacturer", el == null || el.manufacturer == null ? "" : el.manufacturer);
            v.put("driveMode", el == null || el.driveMode == null ? "" : el.driveMode);
            v.put("ratedLoadSpec", el == null || el.ratedLoad == null ? ""
                    : el.ratedLoad + " " + (el.ratedLoadUnit == null ? "kg" : el.ratedLoadUnit));
            v.put("ratedSpeedSpec", el == null || el.ratedSpeed == null ? ""
                    : el.ratedSpeed.stripTrailingZeros().toPlainString() + " "
                    + (el.ratedSpeedUnit == null ? "m/s" : el.ratedSpeedUnit));
        }
    }

    /** 额定载重/速度组合文案（急修单 PDF 用） */
    private String ratedSpec(Elevator el) {
        StringBuilder sb = new StringBuilder();
        if (el.ratedLoad != null) {
            sb.append(el.ratedLoad).append(el.ratedLoadUnit == null ? "kg" : el.ratedLoadUnit);
        }
        if (el.ratedSpeed != null) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(el.ratedSpeed).append(el.ratedSpeedUnit == null ? "m/s" : el.ratedSpeedUnit);
        }
        return sb.toString();
    }

    private String useUnitName(String useUnitId) {
        var uu = useUnitMapper.selectById(useUnitId);
        return uu == null || uu.unitName == null ? "" : uu.unitName;
    }

    private Set<String> requireFaultReadAccess(Fault f, String empId) {
        Set<String> creatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
        boolean visible = creatorIds == null || f.createdBy == null || creatorIds.contains(f.createdBy)
                || "OPEN".equals(f.status) || java.util.Objects.equals(empId, f.dispatchWorkerId);
        if (!visible) {
            throw new BizException(1403, "仅可查看本人、本班组或待认领的急修单");
        }
        return creatorIds;
    }

    private void requireFaultOperator(Fault f, String empId) {
        Set<String> creatorIds = requireFaultReadAccess(f, empId);
        // creatorIds=null 与列表一致，表示管理角色不限数据域；普通作业人员只能操作派给本人的单。
        if (creatorIds != null && !java.util.Objects.equals(empId, f.dispatchWorkerId)) {
            throw new BizException(1403, "仅被派单作业人员或管理角色可操作急修单");
        }
    }

    public Map<String, Object> getFault(String id, String empId) {
        Fault f = faultOr404(id);
        requireFaultReadAccess(f, empId);
        Map<String, Object> view = faultView(f);
        enrichFaultViews(List.of(view));
        return view;
    }

    /** 维修过程字段（小程序急修单详情页）：现场情况/待办事项/处理结果/维修结束时间 */
    public Map<String, Object> updateFault(String id, Map<String, Object> body, String empId) {
        Fault f = faultOr404(id);
        requireFaultOperator(f, empId);
        if (!List.of("OPEN", "ASSIGNED", "PROCESSING").contains(f.status)) {
            throw new BizException(1003, "当前状态不允许修改维修过程或处理结果");
        }
        if (body.get("siteDesc") != null) {
            f.siteDesc = strOrEmpty(body.get("siteDesc"));
        }
        if (body.get("todoDesc") != null) {
            f.todoDesc = strOrEmpty(body.get("todoDesc"));
        }
        if (body.get("handleDesc") != null) {
            f.handleDesc = strOrEmpty(body.get("handleDesc"));
        }
        if (body.get("finishedAt") != null) {
            f.finishedAt = TimeUtil.parse(strOrEmpty(body.get("finishedAt")));
        }
        faultMapper.updateById(f);
        Map<String, Object> view = faultView(f);
        enrichFaultViews(List.of(view));
        return view;
    }

    public Map<String, Object> closeFault(String id, Map<String, Object> body, String empId) {
        Fault f = faultOr404(id);
        requireFaultOperator(f, empId);
        if ("CLOSED".equals(f.status)) {
            return faultView(f);
        }
        if (!"PENDING_CONFIRM".equals(f.status)) {
            throw new BizException(1003, "仅待使用单位确认的急修单可执行旧版关闭");
        }
        String result = strOrEmpty(body == null ? null : body.get("result"));
        String signature = strOrEmpty(body == null ? null : body.get("signature"));
        if (WorkOrderService.isBlank(result)) {
            throw new BizException(422, "请填写处理结果");
        }
        if (WorkOrderService.isBlank(signature)) {
            throw new BizException(422, "请先由使用单位安全管理员签字确认");
        }
        f.status = "CLOSED";
        f.handleDesc = result;
        if (f.finishedAt == null) {
            f.finishedAt = TimeUtil.now();
        }
        f.signature = signature;
        f.confirmedAt = TimeUtil.now();
        faultMapper.updateById(f);
        return map("ok", true);
    }

    // ── 应急演练 ──
    public Map<String, Object> listDrills() {
        List<Drill> drills = drillMapper.selectList(new LambdaQueryWrapper<Drill>()
                .orderByDesc(Drill::getDrillDate));
        long halfYearAgo = System.currentTimeMillis() - 182L * 86400000;
        List<String> covered = new ArrayList<>();
        for (Drill d : drills) {
            if (d.drillDate != null && TimeUtil.toMillis(d.drillDate.atStartOfDay()) >= halfYearAgo
                    && !covered.contains(d.category)) {
                covered.add(d.category);
            }
        }
        List<String> all = elevatorMapper.selectList(null).stream()
                .map(e -> e.category).filter(c -> c != null && !c.isEmpty())
                .distinct().toList();
        List<String> missing = all.stream().filter(c -> !covered.contains(c)).toList();
        List<Map<String, Object>> views = drills.stream().map(this::drillView).toList();
        return map("list", views, "coverage", map("covered", covered, "missing", missing));
    }

    private Map<String, Object> drillView(Drill d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("drillDate", TimeUtil.formatDate(d.drillDate));
        m.put("category", d.category);
        m.put("scene", d.scene);
        m.put("participants", d.participants);
        m.put("process", d.process);
        m.put("problems", d.problems);
        m.put("actions", d.actions);
        m.put("createdAt", TimeUtil.format(d.createdAt));
        return m;
    }

    public Map<String, Object> createDrill(Map<String, Object> body) {
        String drillDate = str(body.get("drillDate"));
        if (drillDate == null || drillDate.isEmpty()) {
            throw new BizException(422, "请填写演练日期");
        }
        String category = str(body.get("category"));
        if (category == null || category.isEmpty()) {
            throw new BizException(422, "请选择电梯品种");
        }
        Drill d = new Drill();
        d.id = Ids.next("dr");
        d.drillDate = TimeUtil.parseDate(drillDate);
        d.category = category;
        d.scene = strOrEmpty(body.get("scene"));
        d.participants = strOrEmpty(body.get("participants"));
        d.process = strOrEmpty(body.get("process"));
        d.problems = strOrEmpty(body.get("problems"));
        d.actions = strOrEmpty(body.get("actions"));
        d.createdAt = TimeUtil.now();
        drillMapper.insert(d);
        return drillView(d);
    }

    // ── 自行检查 ──
    public List<Map<String, Object>> listInspects() {
        LocalDate now = LocalDate.now(TimeUtil.ZONE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Elevator el : elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .orderByAsc(Elevator::getId))) {
            InspectRecord rec = inspectMapper.selectOne(new LambdaQueryWrapper<InspectRecord>()
                    .eq(InspectRecord::getElevatorId, el.id)
                    .orderByDesc(InspectRecord::getInspectDate).last("LIMIT 1"));
            String status = "未检";
            if (rec != null) {
                status = "已完成";
            } else if (el.nextCheckDate != null && el.nextCheckDate.minusDays(30).isBefore(now)) {
                status = "逾期未检";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("elevatorId", el.id);
            m.put("elevatorName", el.elevatorName);
            m.put("elevatorCode", el.elevatorCode);
            m.put("category", el.category == null ? "" : el.category);
            m.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
            m.put("lastInspectDate", rec == null ? "" : TimeUtil.formatDate(rec.inspectDate));
            m.put("status", status);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> inspectTemplate(String elevatorId) {
        Elevator el = elevatorId == null || elevatorId.isEmpty() ? null : elevatorMapper.selectById(elevatorId);
        String appendix = checklistService.appendix(el == null ? "" : el.category);
        return map("appendix", appendix, "items", checklistService.templateItems(appendix));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createInspect(Map<String, Object> body) {
        List<Map<String, Object>> items = body.get("items") == null
                ? List.of() : (List<Map<String, Object>>) body.get("items");
        if (items.isEmpty()) {
            throw new BizException(422, "请至少填写 1 项检查结果");
        }
        long unmarked = items.stream().filter(i -> WorkOrderService.isBlank(str(i.get("result")))).count();
        if (unmarked > 0) {
            throw new BizException(422, "尚有 " + unmarked + " 项未填写检查结果");
        }
        List<Map<String, Object>> abnormal = items.stream()
                .filter(i -> "ABNORMAL".equals(str(i.get("result")))).toList();
        long noDesc = abnormal.stream().filter(i -> WorkOrderService.isBlank(str(i.get("abnormalDesc")))).count();
        if (noDesc > 0) {
            throw new BizException(422, "不合格项请填写问题描述");
        }
        String inspectorSign = str(body.get("inspectorSign"));
        String reviewerSign = str(body.get("reviewerSign"));
        if (WorkOrderService.isBlank(inspectorSign)) {
            throw new BizException(422, "请完成检查人员签字");
        }
        if (WorkOrderService.isBlank(reviewerSign)) {
            throw new BizException(422, "请完成审核人员签字");
        }
        InspectRecord r = new InspectRecord();
        r.id = Ids.next("in");
        r.elevatorId = strOrEmpty(body.get("elevatorId"));
        r.inspectDate = LocalDate.now(TimeUtil.ZONE);
        r.itemTotal = items.size();
        r.abnormalCount = abnormal.size();
        r.problems = abnormal.stream()
                .map(i -> str(i.get("name")) + "：" + str(i.get("abnormalDesc")))
                .reduce((a, b) -> a + "；" + b).orElse("");
        r.inspectorSign = inspectorSign;
        r.reviewerSign = reviewerSign;
        inspectMapper.insert(r);
        return JsonUtil.map(
                "id", r.id,
                "elevatorId", r.elevatorId,
                "inspectDate", TimeUtil.formatDate(r.inspectDate),
                "itemTotal", r.itemTotal,
                "abnormalCount", r.abnormalCount,
                "problems", r.problems,
                "inspectorSign", r.inspectorSign,
                "reviewerSign", r.reviewerSign);
    }

    // ── 消息 ──
    // V56：员工消息中心只读 use_unit_id IS NULL 的行——use_unit_id 非空为客户门户受众
    // （签字临期/超期提醒，ClientMessageService/SignReminderService），两侧互不串扰
    public Map<String, Object> listMessages(Map<String, String> query) {
        List<Map<String, Object>> views = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .isNull(Message::getUseUnitId)
                        .orderByDesc(Message::getCreatedAt)).stream()
                .map(this::messageView).toList();
        return workOrderService.paginate(views, query);
    }

    private Map<String, Object> messageView(Message msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id);
        m.put("title", msg.title);
        m.put("content", msg.content);
        m.put("createdAt", TimeUtil.format(msg.createdAt));
        m.put("read", Boolean.TRUE.equals(msg.readFlag));
        return m;
    }

    public Map<String, Object> unreadCount() {
        Long c = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .isNull(Message::getUseUnitId)
                .eq(Message::getReadFlag, false));
        return map("count", c == null ? 0 : c);
    }

    public Map<String, Object> readMessage(String id) {
        Message msg = messageMapper.selectById(id);
        // 员工中心只可读员工消息：客户消息（use_unit_id 非空）与不存在同 1404（无存在性 oracle），
        // 与 list/unreadCount/readAll 的 use_unit_id IS NULL 谓词同口径（2026-11-21 审查补齐）
        if (msg == null || msg.useUnitId != null) {
            throw new BizException(1404, "消息不存在");
        }
        msg.readFlag = true;
        messageMapper.updateById(msg);
        return map("ok", true);
    }

    public Map<String, Object> readAll() {
        for (Message msg : messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .isNull(Message::getUseUnitId)
                .eq(Message::getReadFlag, false))) {
            msg.readFlag = true;
            messageMapper.updateById(msg);
        }
        return map("ok", true);
    }

    // ── 知识库 ──
    public Map<String, Object> listKnowledge(Map<String, String> query) {
        List<Knowledge> list = knowledgeMapper.selectList(null);
        String keyword = query.get("keyword");
        if (keyword != null && !keyword.isEmpty()) {
            String k = keyword.toLowerCase();
            list = list.stream().filter(x -> x.title != null && x.title.toLowerCase().contains(k)).toList();
        }
        List<Map<String, Object>> views = list.stream().map(x ->
                JsonUtil.map("id", x.id, "title", x.title, "tag", x.tag, "content", x.content)).toList();
        return map("list", views, "total", views.size());
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String strOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
