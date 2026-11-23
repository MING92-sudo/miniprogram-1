package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.common.Paginate;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class HandoverService {

    private static final String INIT = "INIT";
    private static final String DOC_SUBMITTING = "DOC_SUBMITTING";
    private static final String INSPECTING = "INSPECTING";
    private static final String RECTIFYING = "RECTIFYING";
    private static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String PENDING_INST_REVIEW = "PENDING_INST_REVIEW";
    private static final String VOIDED = "VOIDED";

    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";

    private static final String APPROVE = "APPROVE";
    private static final String REJECT = "REJECT";
    private static final Map<String, String> NODE_CAPABILITIES = Map.of(
            "INSTALLER_HANDOVER", PermissionMatrix.HANDOVER_SIGN_INSTALL,
            "MAINTENANCE_ASSIGN", PermissionMatrix.HANDOVER_SIGN_MAINT,
            "MAINTENANCE_CONFIRM", PermissionMatrix.HANDOVER_SIGN_MAINT,
            "SITE_INSPECT", PermissionMatrix.HANDOVER_INSPECT);

    private final HandoverOrderMapper orderMapper;
    private final HandoverCheckMapper checkMapper;
    private final HandoverCheckItemMapper checkItemMapper;
    private final HandoverRectificationMapper rectificationMapper;
    private final HandoverSignMapper signMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchService dispatchService;
    private final HandoverArchiveSupport archiveSupport;
    private final HandoverChecklistService checklistService;

    @Autowired
    public HandoverService(HandoverOrderMapper orderMapper,
                           HandoverCheckMapper checkMapper,
                           HandoverCheckItemMapper checkItemMapper,
                           HandoverRectificationMapper rectificationMapper,
                           HandoverSignMapper signMapper,
                           ElevatorMapper elevatorMapper,
                           EmployeeMapper employeeMapper,
                           WorkOrderMapper workOrderMapper,
                           DispatchService dispatchService,
                           HandoverArchiveSupport archiveSupport,
                           HandoverChecklistService checklistService) {
        this.orderMapper = orderMapper;
        this.checkMapper = checkMapper;
        this.checkItemMapper = checkItemMapper;
        this.rectificationMapper = rectificationMapper;
        this.signMapper = signMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchService = dispatchService;
        this.archiveSupport = archiveSupport;
        this.checklistService = checklistService;
    }

    public HandoverService(HandoverOrderMapper orderMapper,
                           HandoverCheckMapper checkMapper,
                           HandoverCheckItemMapper checkItemMapper,
                           HandoverRectificationMapper rectificationMapper,
                           HandoverSignMapper signMapper,
                           ElevatorMapper elevatorMapper,
                           EmployeeMapper employeeMapper,
                           WorkOrderMapper workOrderMapper,
                           DispatchService dispatchService,
                           HandoverArchiveSupport archiveSupport) {
        this(orderMapper, checkMapper, checkItemMapper, rectificationMapper, signMapper, elevatorMapper,
                employeeMapper, workOrderMapper, dispatchService, archiveSupport,
                new HandoverChecklistService(orderMapper, checkMapper, checkItemMapper, rectificationMapper,
                        signMapper, null));
    }

    public Map<String, Object> options() {
        return Map.of("useUnits", archiveSupport.useUnitOptions());
    }

    /** 安装部发起移交：三份资料与使用单位齐全后创建，直接进入 PENDING_INST_REVIEW。 */
    public HandoverOrder init(Map<String, Object> body) {
        String projectName = str(body, "projectName");
        if (blank(projectName)) {
            throw new BizException(422, "项目名称不能为空");
        }
        String useUnitId = trimToNull(str(body, "useUnitId"));
        if (useUnitId == null) {
            throw new BizException(422, "请选择使用单位");
        }
        archiveSupport.requireUseUnit(useUnitId);
        if (blank(str(body, "factoryNumber"))) {
            throw new BizException(422, "厂家出厂编号为归档时 2.7 查询依据，不能为空");
        }
        if (blank(str(body, "insideNumber"))) {
            throw new BizException(422, "单位内编号为设备档案必传项，不能为空");
        }
        String supervisionReportFileId = trimToNull(str(body, "supervisionReportFileId"));
        String maintenanceManualFileId = trimToNull(str(body, "maintenanceManualFileId"));
        String qualityCertificateFileId = trimToNull(str(body, "qualityCertificateFileId"));
        if (supervisionReportFileId == null || maintenanceManualFileId == null || qualityCertificateFileId == null) {
            throw new BizException(422, "监督检验报告、维护使用说明书、安装质量证明文件缺一不可");
        }
        HandoverOrder o = new HandoverOrder();
        o.id = Ids.next("ho");
        o.handoverNo = Ids.next("ho");
        o.createdAt = LocalDateTime.now();
        o.status = PENDING_INST_REVIEW;
        o.projectName = projectName.trim();
        o.useUnitId = useUnitId;
        o.supervisionReportFileId = supervisionReportFileId;
        o.maintenanceManualFileId = maintenanceManualFileId;
        o.qualityCertificateFileId = qualityCertificateFileId;
        // elevatorId 只允许 archive() 回填，起草时传入一律忽略，禁止绕过建挡与首保排期。
        o.supervisionPassDate = dateOrNull(body == null ? null : body.get("supervisionPassDate"));
        o.initiatorId = trimToNull(str(body, "initiatorId"));
        o.initiatorName = trimToNull(str(body, "initiatorName"));
        o.elevatorCode = trimToNull(str(body, "elevatorCode"));
        o.elevatorName = trimToNull(str(body, "elevatorName"));
        o.category = trimToNull(str(body, "category"));
        o.location = trimToNull(str(body, "location"));
        o.regCode = trimToNull(str(body, "regCode"));
        o.deviceCode = trimToNull(str(body, "deviceCode"));
        o.insideNumber = trimToNull(str(body, "insideNumber"));
        o.model = trimToNull(str(body, "model"));
        o.brand = trimToNull(str(body, "brand"));
        o.manufacturer = trimToNull(str(body, "manufacturer"));
        o.factoryNumber = trimToNull(str(body, "factoryNumber"));
        o.driveMode = trimToNull(str(body, "driveMode"));
        o.ratedLoad = intOrNull(body == null ? null : body.get("ratedLoad"));
        o.ratedLoadUnit = trimToNull(str(body, "ratedLoadUnit"));
        o.ratedSpeed = decimalOrNull(body == null ? null : body.get("ratedSpeed"));
        o.ratedSpeedUnit = trimToNull(str(body, "ratedSpeedUnit"));
        o.stationsDoors = trimToNull(str(body, "stationsDoors"));
        o.workTypeCode = trimToNull(str(body, "workTypeCode"));
        o.installCompleteDate = trimToNull(str(body, "installCompleteDate"));
        o.installUnitName = trimToNull(str(body, "installUnitName"));
        o.specialType = trimToNull(str(body, "specialType"));
        orderMapper.insert(o);
        return o;
    }

    /** 三份资料齐全后转待安装部审核。 */
    public HandoverOrder submitDocs(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, DOC_SUBMITTING, "提交资料");
        String sr = str(body, "supervisionReportFileId");
        String mm = str(body, "maintenanceManualFileId");
        String qc = str(body, "qualityCertificateFileId");
        if (blank(sr) || blank(mm) || blank(qc)) {
            throw new BizException(422, "监督检验报告、维保说明书、整机质量证明缺一不可");
        }
        o.supervisionReportFileId = sr.trim();
        o.maintenanceManualFileId = mm.trim();
        o.qualityCertificateFileId = qc.trim();
        o.status = PENDING_INST_REVIEW;
        orderMapper.updateById(o);
        return o;
    }

    /** 安装部审核：通过进入现场查验，退回须填写原因和整改期限。 */
    public HandoverOrder review(String id, Map<String, Object> body, String actorEmpId) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, PENDING_INST_REVIEW, "内部审核");
        String action = str(body, "action") == null ? "" : str(body, "action").trim().toUpperCase();
        if (!APPROVE.equals(action) && !REJECT.equals(action)) {
            throw new BizException(422, "审核动作非法（APPROVE/REJECT）：" + action);
        }
        Employee actor = requireActor(actorEmpId);
        if (REJECT.equals(action)) {
            String remark = trimToNull(str(body, "remark"));
            String deadline = trimToNull(str(body, "deadline"));
            if (remark == null) {
                throw new BizException(422, "审核退回须填写原因");
            }
            if (deadline == null) {
                throw new BizException(422, "审核退回须填写整改期限");
            }
            o.reviewRemark = remark;
            o.reviewDeadline = deadline;
            o.status = DOC_SUBMITTING;
        } else {
            o.status = INSPECTING;
        }
        o.reviewById = actor.id;
        o.reviewByName = actor.name;
        o.reviewedAt = LocalDateTime.now();
        orderMapper.updateById(o);
        return o;
    }

    /** 未归档移交单可作废，须填写原因。 */
    public HandoverOrder voidHandover(String id, Map<String, Object> body, String actorEmpId) {
        HandoverOrder o = requireOrder(id);
        if (ARCHIVED.equals(o.status) || VOIDED.equals(o.status)) {
            throw new BizException(422, "已归档或已作废的移交单不可作废");
        }
        String reason = trimToNull(str(body, "reason"));
        if (reason == null) {
            throw new BizException(422, "作废须填写原因");
        }
        Employee actor = requireActor(actorEmpId);
        o.voidReason = reason;
        o.voidById = actor.id;
        o.voidByName = actor.name;
        o.voidedAt = LocalDateTime.now();
        o.status = VOIDED;
        orderMapper.updateById(o);
        return o;
    }

    private Employee requireActor(String actorEmpId) {
        Employee actor = actorEmpId == null || actorEmpId.isBlank() ? null : employeeMapper.selectById(actorEmpId);
        if (actor == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return actor;
    }

    public HandoverOrder assignReceiver(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, INSPECTING, "分配接收人员");
        String receiverId = str(body, "receiverId");
        if (blank(receiverId)) {
            throw new BizException(422, "请指定接收工程师");
        }
        o.receiverId = receiverId.trim();
        o.receiverName = trimToNull(str(body, "receiverName"));
        orderMapper.updateById(o);
        return o;
    }

    /** 旧入口仅保留路由兼容，查验结果统一走 74 项清单校验。 */
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public HandoverOrder submitInspect(String id, Map<String, Object> body) {
        Object conditional = body == null ? null : body.get("conditional");
        Map<String, Object> cond = conditional instanceof Map<?, ?> ? (Map<String, Object>) conditional : null;
        return checklistService.submitChecklist(id, listOfMaps(body == null ? null : body.get("items")), cond);
    }

    public HandoverOrder submitRectify(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, RECTIFYING, "上传整改");
        List<Map<String, Object>> rects = listOfMaps(body == null ? null : body.get("rectifications"));
        if (rects.isEmpty()) {
            throw new BizException(422, "请上传整改前后照片");
        }
        for (Map<String, Object> r : rects) {
            String type = trimToNull(str(r, "itemType"));
            if (type == null) {
                continue;
            }
            HandoverRectification rect = new HandoverRectification();
            rect.id = Ids.next("hr");
            rect.handoverId = o.id;
            rect.itemType = type;
            rect.beforePhotoFileId = trimToNull(str(r, "beforePhotoFileId"));
            rect.afterPhotoFileId = trimToNull(str(r, "afterPhotoFileId"));
            rectificationMapper.insert(rect);
        }
        return o;
    }

    public HandoverOrder recheckRectify(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, RECTIFYING, "复验");
        String result = trimToNull(str(body, "result"));
        if (result == null || (!PASS.equals(result) && !FAIL.equals(result))) {
            throw new BizException(422, "复验结果应为 PASS 或 FAIL");
        }
        String recheckerId = trimToNull(str(body, "recheckerId"));
        String recheckerName = trimToNull(str(body, "recheckerName"));
        LocalDateTime now = LocalDateTime.now();
        String rectId = trimToNull(str(body, "rectId"));
        if (rectId != null) {
            HandoverRectification one = rectificationMapper.selectById(rectId);
            if (one == null || !o.id.equals(one.handoverId)) {
                throw new BizException(404, "整改记录不存在");
            }
            one.recheckResult = result;
            one.recheckerId = recheckerId;
            one.recheckerName = recheckerName;
            one.recheckedAt = now;
            rectificationMapper.updateById(one);
            List<HandoverRectification> all = rectificationMapper.selectList(
                    new LambdaQueryWrapper<HandoverRectification>().eq(HandoverRectification::getHandoverId, o.id));
            boolean allPass = !all.isEmpty() && all.stream().allMatch(r -> PASS.equals(r.recheckResult));
            o.status = allPass ? PENDING_CONFIRM : RECTIFYING;
            orderMapper.updateById(o);
            return o;
        }
        List<HandoverRectification> outstanding = rectificationMapper.selectList(
                new LambdaQueryWrapper<HandoverRectification>()
                        .eq(HandoverRectification::getHandoverId, o.id)
                        .isNull(HandoverRectification::getRecheckResult));
        if (outstanding != null) {
            for (HandoverRectification r : outstanding) {
                r.recheckResult = result;
                r.recheckerId = recheckerId;
                r.recheckerName = recheckerName;
                r.recheckedAt = now;
                rectificationMapper.updateById(r);
            }
        }
        o.status = PASS.equals(result) ? PENDING_CONFIRM : RECTIFYING;
        orderMapper.updateById(o);
        return o;
    }

    /** 确认后归档，并在同一事务创建电梯档案和首保工单。 */
    @Transactional(rollbackFor = Exception.class)
    public HandoverOrder confirm(String id, Map<String, Object> body, String actorEmpId) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, PENDING_CONFIRM, "确认");
        Employee actor = employeeMapper.selectById(actorEmpId);
        if (actor == null) {
            throw new BizException(1403, "确认人登录身份无效");
        }
        checklistService.assertArchivable(id);
        o.confirmedById = actor.id;
        o.confirmedByName = actor.name;
        o.confirmedAt = LocalDateTime.now();
        o.status = ARCHIVED;
        orderMapper.updateById(o);
        return archive(o.id);
    }

    /** 归档创建电梯档案和首保工单；elevatorId 非空时直接返回。 */
    @Transactional(rollbackFor = Exception.class)
    public HandoverOrder archive(String handoverId) {
        HandoverOrder order = requireOrder(handoverId);
        if (order.elevatorId != null) {
            return order;
        }
        Elevator el = new Elevator();
        el.id = Ids.next("el");
        el.elevatorCode = order.elevatorCode;
        el.elevatorName = order.elevatorName;
        el.category = order.category;
        el.location = order.location;
        el.regCode = order.regCode;
        el.deviceCode = order.deviceCode;
        el.insideNumber = order.insideNumber;
        el.model = order.model;
        el.brand = order.brand;
        el.manufacturer = order.manufacturer;
        el.factoryNumber = order.factoryNumber;
        el.driveMode = order.driveMode;
        el.ratedLoad = order.ratedLoad;
        el.ratedLoadUnit = order.ratedLoadUnit;
        el.ratedSpeed = order.ratedSpeed;
        el.ratedSpeedUnit = order.ratedSpeedUnit;
        el.stationsDoors = order.stationsDoors;
        el.workTypeCode = order.workTypeCode;
        el.projectName = order.projectName;
        el.useUnitId = order.useUnitId;
        el.specialType = order.specialType;
        el.supervisionPassDate = order.supervisionPassDate;
        el.inspectionReportFileId = order.supervisionReportFileId;
        el.inspectionReportUploadedAt = LocalDateTime.now();
        el.archiveSource = 1;
        el.handoverAt = LocalDateTime.now();
        archiveSupport.enrich(order, el);
        if (el.elevatorName == null || el.elevatorName.isBlank()) {
            el.elevatorName = el.insideNumber == null || el.insideNumber.isBlank()
                    ? el.elevatorCode : el.insideNumber;
        }
        DeviceProfileValidator.requireForCreate(el);
        // 首保工单进入区域池，不绑定维保人员。
        elevatorMapper.insert(el);
        order.elevatorId = el.id;
        orderMapper.updateById(order);
        WorkOrder first = dispatchService.precreateFirstOrder(el, LocalDate.now().plusDays(15));
        if (first != null) {
            first.sourceHandoverId = order.id;
            workOrderMapper.updateById(first);
        }
        return order;
    }

    /** 仅授权角色可签对应节点；签字只留痕，不驱动状态流转。 */
    @Transactional(rollbackFor = Exception.class)
    public HandoverSign signNode(String handoverId, Map<String, Object> body, String actorEmpId) {
        HandoverOrder order = requireOrder(handoverId);
        String nodeType = str(body, "nodeType") == null ? "" : str(body, "nodeType").trim();
        String capability = NODE_CAPABILITIES.get(nodeType);
        if (capability == null) {
            throw new BizException(422, "未知签字节点：" + nodeType);
        }
        String action = str(body, "action") == null ? "" : str(body, "action").trim().toUpperCase();
        if (!APPROVE.equals(action) && !REJECT.equals(action)) {
            throw new BizException(422, "签字动作非法（APPROVE/REJECT）：" + action);
        }
        Employee actor = actorEmpId == null || actorEmpId.isBlank()
                ? null : employeeMapper.selectById(actorEmpId);
        if (actor == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        if (PermissionMatrix.state(actor.role, capability) != PermissionMatrix.TriState.Y) {
            throw new BizException(1403, "仅授权岗位可签「" + nodeType + "」节点");
        }
        HandoverSign sign = new HandoverSign();
        sign.id = Ids.next("hs");
        sign.handoverId = order.id;
        sign.nodeType = nodeType;
        sign.actorId = actor.id;
        sign.actorName = actor.name;
        sign.action = action;
        sign.at = LocalDateTime.now();
        signMapper.insert(sign);
        return sign;
    }

    public Map<String, Object> list(Map<String, String> query) {
        return list(query, null);
    }

    /** 纯维保角色未指定状态时，仅返回验收阶段单据。 */
    public Map<String, Object> list(Map<String, String> query, java.util.Set<String> roles) {
        String status = query == null ? null : query.get("status");
        boolean maintenanceOnly = roles != null && !roles.isEmpty()
                && roles.stream().anyMatch(r -> r.startsWith("MAINT"))
                && roles.stream().noneMatch(r -> r.startsWith("INST") || "INSTALLER".equals(r)
                        || "PLATFORM_ADMIN".equals(r) || "SYS_ADMIN".equals(r) || "ADMIN".equals(r));
        boolean explicitStatus = status != null && !status.isBlank();
        List<HandoverOrder> all = orderMapper.selectList(new LambdaQueryWrapper<HandoverOrder>()
                .orderByDesc(HandoverOrder::getCreatedAt));
        List<HandoverOrder> filtered = (all == null ? List.<HandoverOrder>of() : all).stream()
                .filter(o -> explicitStatus ? status.equals(o.status)
                        : !(maintenanceOnly && !MAINT_VISIBLE_STATUSES.contains(o.status)))
                .sorted(java.util.Comparator.comparing(
                        (HandoverOrder o) -> o.createdAt == null ? LocalDateTime.MIN : o.createdAt).reversed())
                .toList();
        return Paginate.of(filtered, query == null ? Map.of() : query);
    }

    private static final java.util.Set<String> MAINT_VISIBLE_STATUSES = java.util.Set.of(
            "INSPECTING", "RECTIFYING", "PENDING_CONFIRM", "ARCHIVED");

    /** 详情聚合查验、整改和签字记录。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detail(String id) {
        HandoverOrder o = requireOrder(id);
        Map<String, Object> out = com.cqwlw.maintenance.util.JsonUtil.MAPPER.convertValue(o, Map.class);
        List<HandoverCheck> checks = checkMapper.selectList(new LambdaQueryWrapper<HandoverCheck>()
                .eq(HandoverCheck::getHandoverId, id).orderByDesc(HandoverCheck::getCheckedAt));
        List<Map<String, Object>> checkViews = new ArrayList<>();
        for (HandoverCheck c : checks == null ? List.<HandoverCheck>of() : checks) {
            Map<String, Object> m = new LinkedHashMap<>(com.cqwlw.maintenance.util.JsonUtil.MAPPER.convertValue(c, Map.class));
            List<HandoverCheckItem> items = checkItemMapper.selectList(new LambdaQueryWrapper<HandoverCheckItem>()
                    .eq(HandoverCheckItem::getCheckId, c.id));
            m.put("items", items == null ? List.of() : items);
            checkViews.add(m);
        }
        out.put("checks", checkViews);
        List<HandoverRectification> rects = rectificationMapper.selectList(new LambdaQueryWrapper<HandoverRectification>()
                .eq(HandoverRectification::getHandoverId, id));
        out.put("rectifications", rects == null ? List.of() : rects);
        List<HandoverSign> signs = signMapper.selectList(new LambdaQueryWrapper<HandoverSign>()
                .eq(HandoverSign::getHandoverId, id).orderByDesc(HandoverSign::getAt));
        out.put("signs", signs == null ? List.of() : signs);
        return out;
    }

    public HandoverOrder get(String id) {
        return requireOrder(id);
    }

    private HandoverOrder requireOrder(String id) {
        HandoverOrder o = orderMapper.selectById(id);
        if (o == null) {
            throw new BizException(1404, "移交单不存在");
        }
        return o;
    }

    private static void requireStatus(HandoverOrder o, String expected, String action) {
        if (!expected.equals(o.status)) {
            throw new BizException(422, "当前状态不允许" + action);
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定载重必须为数字");
        }
    }

    private static BigDecimal decimalOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定速度必须为数字");
        }
    }

    private static LocalDate dateOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new BizException(422, "监督检验合格日应为 yyyy-MM-dd");
        }
    }

    private static List<Map<String, Object>> listOfMaps(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .filter(Map.class::isInstance)
                    .map(e -> (Map<String, Object>) e)
                    .toList();
        }
        return List.of();
    }
}
