package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Drill;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.Knowledge;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.Rescue;
import com.cqwlw.maintenance.mapper.DrillMapper;
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

    public DirectoryService(RescueMapper rescueMapper, FaultMapper faultMapper,
                            DrillMapper drillMapper, InspectRecordMapper inspectMapper,
                            MessageMapper messageMapper, KnowledgeMapper knowledgeMapper,
                            ElevatorMapper elevatorMapper, ChecklistService checklistService,
                            WorkOrderService workOrderService, EmployeeScopeService scopeService,
                            EmployeeMapper employeeMapper, UseUnitMapper useUnitMapper,
                            WorkOrderMapper workOrderMapper) {
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
        f.faultType = strOrEmpty(body.get("faultType"));
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
        faultMapper.insert(f);
        return faultView(f);
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
        m.put("desc", f.descr);
        m.put("siteDesc", f.siteDesc == null ? "" : f.siteDesc);
        m.put("arrivedAt", TimeUtil.format(f.arrivedAt));
        m.put("finishedAt", TimeUtil.format(f.finishedAt));
        m.put("todoDesc", f.todoDesc == null ? "" : f.todoDesc);
        m.put("photos", JsonUtil.readStringList(f.photos));
        m.put("status", f.status);
        m.put("result", f.handleDesc);
        m.put("signature", f.signature == null ? "" : f.signature);
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
            list = list.stream().filter(f -> f.createdBy == null || creatorIds.contains(f.createdBy)).toList();
        }
        String status = query.get("status");
        if (status != null && !status.isEmpty()) {
            list = list.stream().filter(f -> status.equals(f.status)).toList();
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
            v.put("createdByName", by.isEmpty() ? "" : names.getOrDefault(by, by));
            v.put("reporterPhone", by.isEmpty() ? "" : phones.getOrDefault(by, ""));
            Elevator el = elevators.get(String.valueOf(v.get("elevatorCode")));
            v.put("elevatorName", el == null || el.elevatorName == null ? "" : el.elevatorName);
            v.put("useUnitName", el == null || el.useUnitId == null ? "" : useUnitName(el.useUnitId));
            // PDF 电梯基本信息栏（样单：扫码自动带出，不可手改）
            v.put("regCode", el == null || el.regCode == null ? "" : el.regCode);
            v.put("model", el == null || el.model == null ? "" : el.model);
            v.put("location", el == null || el.location == null ? "" : el.location);
            v.put("stationsDoors", el == null || el.stationsDoors == null ? "" : el.stationsDoors);
            v.put("ratedSpec", el == null ? "" : ratedSpec(el));
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

    public Map<String, Object> getFault(String id, String empId) {
        Fault f = faultMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "故障记录不存在");
        }
        Set<String> creatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
        if (f.createdBy != null && creatorIds != null && !creatorIds.contains(f.createdBy)) {
            throw new BizException(1403, "仅可查看本人或本班组急修单");
        }
        Map<String, Object> view = faultView(f);
        enrichFaultViews(List.of(view));
        return view;
    }

    /** 维修过程字段（小程序急修单详情页）：现场情况/待办事项/处理结果/维修结束时间 */
    public Map<String, Object> updateFault(String id, Map<String, Object> body, String empId) {
        Fault f = faultMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "急修单不存在");
        }
        Set<String> creatorIds = scopeService.visibleCreatorIds(scopeService.require(empId));
        if (f.createdBy != null && creatorIds != null && !creatorIds.contains(f.createdBy)) {
            throw new BizException(1403, "仅可操作本人或本班组急修单");
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
    public Map<String, Object> closeFault(String id, Map<String, Object> body) {
        Fault f = faultMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "故障记录不存在");
        }
        if ("CLOSED".equals(f.status)) {
            return faultView(f);
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
    public Map<String, Object> listMessages(Map<String, String> query) {
        List<Map<String, Object>> views = messageMapper.selectList(new LambdaQueryWrapper<Message>()
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
                .eq(Message::getReadFlag, false));
        return map("count", c == null ? 0 : c);
    }

    public Map<String, Object> readMessage(String id) {
        Message msg = messageMapper.selectById(id);
        if (msg == null) {
            throw new BizException(1404, "消息不存在");
        }
        msg.readFlag = true;
        messageMapper.updateById(msg);
        return map("ok", true);
    }

    public Map<String, Object> readAll() {
        for (Message msg : messageMapper.selectList(new LambdaQueryWrapper<Message>()
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
