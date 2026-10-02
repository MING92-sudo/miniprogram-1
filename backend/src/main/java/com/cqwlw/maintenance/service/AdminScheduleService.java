package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainPlan;
import com.cqwlw.maintenance.entity.PlanDelay;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainPlanMapper;
import com.cqwlw.maintenance.mapper.PlanDelayMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端·计划调度与延期审批：到期电梯生成排班池（maintain_plan）→ 指派（校验
 * platform_id 未同步 1004、主/配合互斥且均 ≠ 维保经理 1007、已有未完成工单 1007）
 * → 生成工单（auto_dispatched=false）。
 * 延期：LEADER+ 发起 → ADMIN 审批；最近记录已上报时平台侧维保日期已固化，
 * 响应返回 platformDateSynced=false 提示。
 */
@Service
public class AdminScheduleService {

    private final MaintainPlanMapper planMapper;
    private final PlanDelayMapper delayMapper;
    private final WorkOrderMapper orderMapper;
    private final OrderNoIssuer orderNoIssuer;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final UseUnitMapper useUnitMapper;
    private final com.cqwlw.maintenance.mapper.CompanyMapper companyMapper;
    private final com.cqwlw.maintenance.mapper.MaintainRecordMapper recordMapper;
    private final com.cqwlw.maintenance.mapper.MessageMapper messageMapper;
    private final ChecklistService checklistService;

    public AdminScheduleService(MaintainPlanMapper planMapper, PlanDelayMapper delayMapper,
                                WorkOrderMapper orderMapper, ElevatorMapper elevatorMapper,
                                EmployeeMapper employeeMapper, UseUnitMapper useUnitMapper,
                                com.cqwlw.maintenance.mapper.CompanyMapper companyMapper,
                                com.cqwlw.maintenance.mapper.MaintainRecordMapper recordMapper,
                                com.cqwlw.maintenance.mapper.MessageMapper messageMapper,
                                ChecklistService checklistService, OrderNoIssuer orderNoIssuer) {
        this.planMapper = planMapper;
        this.delayMapper = delayMapper;
        this.orderMapper = orderMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.useUnitMapper = useUnitMapper;
        this.companyMapper = companyMapper;
        this.recordMapper = recordMapper;
        this.messageMapper = messageMapper;
        this.checklistService = checklistService;
        this.orderNoIssuer = orderNoIssuer;
    }

    // ── 生成排班池（POST /admin/plans/generate）──

    public Map<String, Object> generatePlans(String dateFrom, String dateTo, String useUnitId) {
        LocalDate from = TimeUtil.parseDate(dateFrom);
        LocalDate to = TimeUtil.parseDate(dateTo);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BizException(422, "请提供有效的 dateFrom/dateTo");
        }
        int generated = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Elevator el : elevatorMapper.selectList(null)) {
            if (useUnitId != null && !useUnitId.isBlank()
                    && !useUnitId.equals(el.useUnitId)) {
                continue;
            }
            if (el.workTypeCode == null || el.workTypeCode.isBlank()) {
                skipped.add(skip(el, "未配置维保周期"));
                continue;
            }
            Long active = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getElevatorId, el.id).ne(WorkOrder::getStatus, "DONE"));
            if (active != null && active > 0) {
                skipped.add(skip(el, "已有未完成工单"));
                continue;
            }
            LocalDateTime last = lastCheckout(el.id);
            if (last == null) {
                last = el.lastMaintenanceAt;
            }
            if (last == null) {
                skipped.add(skip(el, "无上次维保日期"));
                continue;
            }
            int intervalDays = el.intervalDays != null ? el.intervalDays : 15;
            LocalDate dueDate = last.toLocalDate().plusDays(intervalDays);
            if (dueDate.isBefore(from) || dueDate.isAfter(to)) {
                skipped.add(skip(el, "到期日 " + dueDate + " 不在范围内"));
                continue;
            }
            Long dup = planMapper.selectCount(new LambdaQueryWrapper<MaintainPlan>()
                    .eq(MaintainPlan::getElevatorId, el.id)
                    .eq(MaintainPlan::getPlanDate, dueDate)
                    .ne(MaintainPlan::getStatus, "CANCELLED"));
            if (dup != null && dup > 0) {
                skipped.add(skip(el, "该到期日已存在计划"));
                continue;
            }
            MaintainPlan p = new MaintainPlan();
            p.id = Ids.next("pl");
            p.elevatorId = el.id;
            p.planDate = dueDate;
            p.workTypeCode = el.workTypeCode;
            p.status = "UNASSIGNED";
            p.createdAt = TimeUtil.now();
            p.updatedAt = TimeUtil.now();
            planMapper.insert(p);
            generated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("skipped", skipped);
        return out;
    }

    // ── 列表 / 详情 ──

    public Map<String, Object> listPlans(Map<String, String> q) {
        LambdaQueryWrapper<MaintainPlan> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("dateFrom"))) {
            w.ge(MaintainPlan::getPlanDate, TimeUtil.parseDate(q.get("dateFrom")));
        }
        if (notBlank(q.get("dateTo"))) {
            w.le(MaintainPlan::getPlanDate, TimeUtil.parseDate(q.get("dateTo")));
        }
        if (notBlank(q.get("status"))) {
            w.eq(MaintainPlan::getStatus, q.get("status"));
        }
        if (notBlank(q.get("elevatorId"))) {
            w.eq(MaintainPlan::getElevatorId, q.get("elevatorId"));
        }
        if (notBlank(q.get("assignee"))) {
            String a = q.get("assignee");
            w.and(x -> x.eq(MaintainPlan::getPrincipalId, a).or().eq(MaintainPlan::getAssistantId, a));
        }
        w.orderByAsc(MaintainPlan::getPlanDate);
        List<MaintainPlan> all = planMapper.selectList(w);

        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (MaintainPlan p : all.subList(from, to)) {
            list.add(planRow(p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    public Map<String, Object> planDetail(String id) {
        MaintainPlan p = requirePlan(id);
        Map<String, Object> row = planRow(p);
        row.put("conflicts", conflicts(id).get("conflicts"));
        return row;
    }

    // ── 指派（PUT /admin/plans/{id}/assign）──

    public Map<String, Object> assign(String planId, String principalId, String assistantId, String planDate) {
        MaintainPlan p = requirePlan(planId);
        if (!"UNASSIGNED".equals(p.status) && !"ASSIGNED".equals(p.status)) {
            throw new BizException(422, "当前状态 " + p.status + " 不可指派");
        }
        LocalDate date = TimeUtil.parseDate(planDate) != null ? TimeUtil.parseDate(planDate) : p.planDate;
        Employee principal = assignable(principalId, "主维保");
        Employee assistant = assistantId == null || assistantId.isBlank()
                ? null : assignable(assistantId, "配合人员");

        // 排班派工前主/配合互斥、且均 ≠ 维保经理（1007）
        checkDispatchMutex(principal, assistant);
        checkCert(principal, date);
        if (assistant != null) {
            checkCert(assistant, date);
        }
        // platform_id 未同步不可派工（1004）
        requirePlatformId(principal);
        if (assistant != null) {
            requirePlatformId(assistant);
        }
        Elevator el = requireElevator(p.elevatorId);
        checkElevatorFree(el);

        p.status = "ASSIGNED";
        p.principalId = principal.id;
        p.assistantId = assistant == null ? null : assistant.id;
        p.planDate = date;
        p.updatedAt = TimeUtil.now();

        WorkOrder o = new WorkOrder();
        o.id = Ids.next("wo");
        o.elevatorId = el.id;
        o.workType = checklistService.label(p.workTypeCode);
        o.workTypeCode = p.workTypeCode;
        o.planTime = LocalDateTime.of(date, java.time.LocalTime.of(9, 0));
        o.status = "PENDING";
        o.workerName = principal.name;
        o.assistantName = assistant == null ? "" : assistant.name;
        o.workerPlatformId = principal.platformId == null ? "" : principal.platformId;
        o.assistantPlatformId = assistant == null || assistant.platformId == null ? "" : assistant.platformId;
        o.autoDispatched = false;
        o.checklistJson = JsonUtil.write(checklistService.buildChecklist(
                p.workTypeCode, el.category, el.specialType));
        orderNoIssuer.insert(o);
        p.orderId = o.id;
        planMapper.updateById(p);

        notify(el, "人工派单通知", el.elevatorName + " " + o.workType + " 已排班（计划 "
                + date + "），维保人员 " + principal.name + "，请按时扫码签到。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", p.id);
        out.put("orderId", o.id);
        out.put("orderNo", o.orderNo);
        out.put("planDate", TimeUtil.formatDate(date));
        out.put("workerName", principal.name);
        return out;
    }

    // ── 转派（POST /admin/orders/{id}/transfer，班组长及以上）──

    public Map<String, Object> transfer(String orderId, String toEmployeeId, String reason) {
        WorkOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(1404, "工单不存在");
        }
        if (!"PENDING".equals(o.status)) {
            throw new BizException(422, "仅待执行工单可转派（当前 " + o.status + "）");
        }
        Employee target = assignable(toEmployeeId, "转派对象");
        requirePlatformId(target);
        LocalDate planDay = o.planTime != null ? o.planTime.toLocalDate() : TimeUtil.now().toLocalDate();
        checkCert(target, planDay);

        // 互斥：转派后主维保 ≠ 配合人员（配合人优先从关联计划取）且 ≠ 维保经理
        Employee assistant = null;
        MaintainPlan linked = planMapper.selectList(new LambdaQueryWrapper<MaintainPlan>()
                .eq(MaintainPlan::getOrderId, o.id).last("LIMIT 1")).stream().findFirst().orElse(null);
        if (linked != null && notBlank(linked.assistantId)) {
            assistant = employeeMapper.selectById(linked.assistantId);
        }
        checkDispatchMutex(target, assistant);
        checkElevatorFree(elevatorMapper.selectById(o.elevatorId), o.id);

        o.workerName = target.name;
        o.workerPlatformId = target.platformId == null ? "" : target.platformId;
        orderMapper.updateById(o);
        if (linked != null) {
            linked.principalId = target.id;
            linked.updatedAt = TimeUtil.now();
            planMapper.updateById(linked);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("orderId", o.id);
        out.put("orderNo", o.orderNo);
        out.put("workerName", target.name);
        out.put("reason", reason == null ? "" : reason);
        return out;
    }

    // ── 延期申请与审批（POST /admin/plans/{id}/delay；PUT /admin/plans/delays/{id}/decide）──

    public Map<String, Object> applyDelay(String planId, String reason, Integer delayDays,
                                          String expectedDate, String applicantId) {
        MaintainPlan p = requirePlan(planId);
        if ("DISPATCHED".equals(p.status) || "CANCELLED".equals(p.status)) {
            throw new BizException(422, "当前状态不可申请延期");
        }
        LocalDate target = TimeUtil.parseDate(expectedDate);
        if (target == null) {
            throw new BizException(422, "请提供期望延至日期 expectedDate（yyyy-MM-dd）");
        }
        PlanDelay d = new PlanDelay();
        d.id = Ids.next("pd");
        d.planId = p.id;
        d.reason = reason == null || reason.isBlank() ? "未填写原因" : reason;
        d.delayDays = delayDays == null ? (int) (TimeUtil.now().toLocalDate().datesUntil(target).count()) : delayDays;
        d.expectedDate = target;
        d.status = "PENDING";
        d.applicantId = applicantId;
        d.platformDateSynced = false;
        d.createdAt = TimeUtil.now();
        delayMapper.insert(d);
        p.status = "POSTPONE_PENDING";
        p.updatedAt = TimeUtil.now();
        planMapper.updateById(p);
        return delayRow(d);
    }

    public Map<String, Object> listDelays(String status) {
        LambdaQueryWrapper<PlanDelay> w = new LambdaQueryWrapper<>();
        if (notBlank(status)) {
            w.eq(PlanDelay::getStatus, status);
        }
        w.orderByDesc(PlanDelay::getCreatedAt);
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlanDelay d : delayMapper.selectList(w)) {
            list.add(delayRow(d));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        return out;
    }

    public Map<String, Object> decideDelay(String delayId, boolean approved, String comment, String approverId) {
        PlanDelay d = delayMapper.selectById(delayId);
        if (d == null) {
            throw new BizException(1404, "延期申请不存在");
        }
        if (!"PENDING".equals(d.status)) {
            throw new BizException(422, "该申请已处理");
        }
        MaintainPlan p = requirePlan(d.planId);
        WorkOrder linkedOrder = p.orderId == null ? null : orderMapper.selectById(p.orderId);
        if (approved && linkedOrder != null && !"PENDING".equals(linkedOrder.status)) {
            throw new BizException(422, "工单已开工（" + linkedOrder.status + "），无法延期");
        }
        // A.5 约束：最近一次维保记录上报成功 → 平台侧 nextMaintenanceDate 已固化
        Elevator el = elevatorMapper.selectById(p.elevatorId);
        boolean platformFixed = false;
        if (el != null && notBlank(el.elevatorCode)) {
            Long reported = recordMapper.selectCount(new LambdaQueryWrapper<com.cqwlw.maintenance.entity.MaintainRecord>()
                    .eq(com.cqwlw.maintenance.entity.MaintainRecord::getElevatorCode, el.elevatorCode)
                    .eq(com.cqwlw.maintenance.entity.MaintainRecord::getReportStatus, "REPORTED"));
            platformFixed = reported != null && reported > 0;
        }
        d.status = approved ? "APPROVED" : "REJECTED";
        d.approverId = approverId;
        d.approveComment = comment == null ? "" : comment;
        d.decidedAt = TimeUtil.now();
        d.platformDateSynced = !platformFixed;
        delayMapper.updateById(d);

        if (approved) {
            p.planDate = d.expectedDate;
            if (linkedOrder != null) {
                linkedOrder.planTime = LocalDateTime.of(d.expectedDate, java.time.LocalTime.of(9, 0));
                orderMapper.updateById(linkedOrder);
            }
        }
        p.status = p.principalId == null ? "UNASSIGNED" : "ASSIGNED";
        p.updatedAt = TimeUtil.now();
        planMapper.updateById(p);

        Map<String, Object> out = delayRow(d);
        out.put("plan", planRow(p));
        out.put("platformDateSynced", d.platformDateSynced);
        out.put("platformFixedNote", platformFixed
                ? "该梯最近记录已上报成功，平台侧下次维保日期已固化，本地延期后需以人工复核为准（docs/04 A.5）"
                : "");
        return out;
    }

    // ── 冲突检测 / 排班建议（GET /admin/plans/conflicts、/admin/plans/schedule-suggestion）──

    public Map<String, Object> conflicts(String planId) {
        MaintainPlan p = requirePlan(planId);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Employee principal = p.principalId == null ? null : employeeMapper.selectById(p.principalId);
        Employee assistant = p.assistantId == null ? null : employeeMapper.selectById(p.assistantId);
        for (Employee e : new Employee[]{principal, assistant}) {
            if (e == null) {
                continue;
            }
            if (e.platformId == null || e.platformId.isBlank()) {
                conflicts.add(Map.of("type", "PLATFORM_ID_MISSING", "code", 1004,
                        "message", e.name + " platform_id 未同步，不可派工"));
            }
            if (e.workEndDate != null && !e.workEndDate.isBlank()
                    && p.planDate != null && TimeUtil.parseDate(e.workEndDate).isBefore(p.planDate)) {
                conflicts.add(Map.of("type", "CERT_EXPIRED", "code", 1007,
                        "message", e.name + " 证件有效期至 " + e.workEndDate + "，早于计划日期"));
            }
            long sameDay = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getWorkerName, e.name)
                    .eq(WorkOrder::getPlanTime, planDayStart(p))
                    .ne(WorkOrder::getStatus, "DONE"));
            if (sameDay > 0) {
                conflicts.add(Map.of("type", "SAME_DAY_OVERLOAD", "message",
                        e.name + " 当日已有 " + sameDay + " 单待执行"));
            }
        }
        if (principal != null && assistant != null
                && notBlank(principal.phone) && principal.phone.equals(assistant.phone)) {
            conflicts.add(Map.of("type", "PHONE_MUTEX", "code", 1007,
                    "message", "主维保与配合人员手机号相同（docs/01 §3.2.4 ④）"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conflicts", conflicts);
        out.put("blocking", conflicts.stream().anyMatch(c -> ((Number) c.getOrDefault("code", 0)).intValue() != 0));
        return out;
    }

    public Map<String, Object> suggestion(String planId) {
        MaintainPlan p = requirePlan(planId);
        LocalDate date = p.planDate;
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getRole, AdminRoles.WORKER, AdminRoles.LEADER))) {
            long open = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getWorkerName, e.name).ne(WorkOrder::getStatus, "DONE"));
            boolean platformOk = notBlank(e.platformId);
            boolean certOk = e.workEndDate == null || e.workEndDate.isBlank()
                    || !TimeUtil.parseDate(e.workEndDate).isBefore(date);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("roleText", e.roleText);
            m.put("openOrders", open);
            m.put("platformOk", platformOk);
            m.put("certOk", certOk);
            m.put("assignable", platformOk && certOk);
            candidates.add(m);
        }
        candidates.sort((a, b) -> Long.compare((long) a.get("openOrders"), (long) b.get("openOrders")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", p.id);
        out.put("planDate", TimeUtil.formatDate(date));
        out.put("candidates", candidates);
        return out;
    }

    // ── 私有 ──

    private Employee assignable(String employeeId, String label) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new BizException(422, label + "必填");
        }
        Employee e = employeeMapper.selectById(employeeId);
        if (e == null) {
            throw new BizException(1404, label + "不存在：" + employeeId);
        }
        if (!AdminRoles.WORKER.equals(e.role) && !AdminRoles.LEADER.equals(e.role)) {
            throw new BizException(422, label + "须为维保人员/班组长：" + e.name);
        }
        return e;
    }

    private void requirePlatformId(Employee e) {
        if (e.platformId == null || e.platformId.isBlank()) {
            throw new BizException(1004, e.name + " platform_id 未同步，不可派工，请先在平台同步页完成 2.5 同步");
        }
    }

    private void checkCert(Employee e, LocalDate planDate) {
        if (e.workEndDate != null && !e.workEndDate.isBlank()
                && TimeUtil.parseDate(e.workEndDate).isBefore(planDate)) {
            throw new BizException(1007, "配置冲突：" + e.name + " 证件有效期至 " + e.workEndDate
                    + "，早于计划日期 " + TimeUtil.formatDate(planDate));
        }
    }

    /** 主/配合互斥、且均 ≠ 维保经理 */
    private void checkDispatchMutex(Employee principal, Employee assistant) {
        if (assistant != null && notBlank(principal.phone) && principal.phone.equals(assistant.phone)) {
            throw new BizException(1007, "配置冲突：主维保（" + principal.name + "）与配合人员（"
                    + assistant.name + "）手机号相同，请改派");
        }
        var company = companyMapper.selectList(null).stream().findFirst().orElse(null);
        String managerPhone = company == null ? null : company.workMenegerPhone;
        if (managerPhone != null && !managerPhone.isBlank()) {
            if (managerPhone.equals(principal.phone)) {
                throw new BizException(1007, "配置冲突：主维保（" + principal.name
                        + "）手机号与维保经理相同，请改派");
            }
            if (assistant != null && managerPhone.equals(assistant.phone)) {
                throw new BizException(1007, "配置冲突：配合人员（" + assistant.name
                        + "）手机号与维保经理相同，请改派");
            }
        }
    }

    private void checkElevatorFree(Elevator el) {
        checkElevatorFree(el, null);
    }

    private void checkElevatorFree(Elevator el, String excludeOrderId) {
        LambdaQueryWrapper<WorkOrder> q = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id)
                .ne(WorkOrder::getStatus, "DONE");
        if (excludeOrderId != null) {
            q.ne(WorkOrder::getId, excludeOrderId);
        }
        Long active = orderMapper.selectCount(q);
        if (active != null && active > 0) {
            throw new BizException(1007, "配置冲突：" + el.elevatorName + " 已有未完成工单，一次只派一单");
        }
    }

    private LocalDateTime lastCheckout(String elevatorId) {
        return orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevatorId)
                .eq(WorkOrder::getStatus, "DONE")
                .isNotNull(WorkOrder::getCheckoutTime)).stream()
                .map(o -> o.checkoutTime)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDateTime planDayStart(MaintainPlan p) {
        return LocalDateTime.of(p.planDate, java.time.LocalTime.MIN).withHour(9);
    }

    private void notify(Elevator el, String title, String content) {
        com.cqwlw.maintenance.entity.Message msg = new com.cqwlw.maintenance.entity.Message();
        msg.id = Ids.next("msg");
        msg.title = title;
        msg.content = content;
        msg.createdAt = TimeUtil.now();
        msg.readFlag = false;
        messageMapper.insert(msg);
    }

    private MaintainPlan requirePlan(String id) {
        MaintainPlan p = planMapper.selectById(id);
        if (p == null) {
            throw new BizException(1404, "维保计划不存在");
        }
        return p;
    }

    private Elevator requireElevator(String elevatorId) {
        Elevator el = elevatorMapper.selectById(elevatorId);
        if (el == null) {
            throw new BizException(1404, "电梯不存在：" + elevatorId);
        }
        return el;
    }

    private Map<String, Object> skip(Elevator el, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elevatorCode", el.elevatorCode);
        m.put("elevatorName", el.elevatorName);
        m.put("reason", reason);
        return m;
    }

    private Map<String, Object> planRow(MaintainPlan p) {
        Elevator el = elevatorMapper.selectById(p.elevatorId);
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Employee principal = p.principalId == null ? null : employeeMapper.selectById(p.principalId);
        Employee assistant = p.assistantId == null ? null : employeeMapper.selectById(p.assistantId);
        WorkOrder order = p.orderId == null ? null : orderMapper.selectById(p.orderId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("elevatorId", p.elevatorId);
        m.put("elevatorCode", el == null ? "" : nz(el.elevatorCode));
        m.put("elevatorName", el == null ? "" : nz(el.elevatorName));
        m.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        m.put("planDate", TimeUtil.formatDate(p.planDate));
        m.put("workTypeCode", nz(p.workTypeCode));
        m.put("workType", checklistService.label(p.workTypeCode));
        m.put("status", nz(p.status));
        m.put("principal", principal == null ? null
                : Map.of("id", nz(principal.id), "name", nz(principal.name), "phone", nz(principal.phone)));
        m.put("assistant", assistant == null ? null
                : Map.of("id", nz(assistant.id), "name", nz(assistant.name), "phone", nz(assistant.phone)));
        m.put("orderId", nz(p.orderId));
        m.put("orderStatus", order == null ? "" : nz(order.status));
        m.put("orderNo", order == null ? "" : nz(order.orderNo));
        return m;
    }

    private Map<String, Object> delayRow(PlanDelay d) {
        MaintainPlan p = planMapper.selectById(d.planId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("planId", d.planId);
        m.put("elevatorName", p == null ? "" : planElevatorName(p));
        m.put("currentPlanDate", p == null ? "" : TimeUtil.formatDate(p.planDate));
        m.put("reason", nz(d.reason));
        m.put("delayDays", d.delayDays == null ? 0 : d.delayDays);
        m.put("expectedDate", TimeUtil.formatDate(d.expectedDate));
        m.put("status", nz(d.status));
        m.put("applicantId", nz(d.applicantId));
        m.put("approverId", nz(d.approverId));
        m.put("approveComment", nz(d.approveComment));
        m.put("createdAt", TimeUtil.format(d.createdAt));
        m.put("decidedAt", TimeUtil.format(d.decidedAt));
        return m;
    }

    private String planElevatorName(MaintainPlan p) {
        Elevator el = elevatorMapper.selectById(p.elevatorId);
        return el == null ? "" : nz(el.elevatorName);
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
