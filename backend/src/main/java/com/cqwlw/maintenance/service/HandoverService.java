package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 移交单（W1，Task 4）状态机 + 3 必传资料卡点 + 现场查验/整改/复验。
 * 6 态：INIT→DOC_SUBMITTING→INSPECTING→(RECTIFYING⇄)→PENDING_CONFIRM→ARCHIVED。
 * 归档副作用（建电梯/首保工单）在 Task 5 的 archive()，本服务只实现到 confirm 的纯状态流转。
 * 角色门禁待 Task 8（INSTALLER/USE_UNIT 角色 + 端点级门禁 + 数据范围谓词），本服务不做角色判断。
 */
@Service
public class HandoverService {

    /** 状态枚举（String，与 handover_order.status 对应） */
    private static final String INIT = "INIT";
    private static final String DOC_SUBMITTING = "DOC_SUBMITTING";
    private static final String INSPECTING = "INSPECTING";
    private static final String RECTIFYING = "RECTIFYING";
    private static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    private static final String ARCHIVED = "ARCHIVED";

    /** 现场查验 6 类 item */
    private static final List<String> ITEM_TYPES = List.of(
            "RUNNING", "SAFETY_DEVICE", "BRAKE", "EMERGENCY_OPERATION", "MACHINE_ROOM_SHAFT", "ACCESSORIES");
    /** 整改触发项：任一为 FAIL 强制转 RECTIFYING */
    private static final Set<String> RECTIFY_ITEM_TYPES = Set.of("SAFETY_DEVICE", "BRAKE");
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";

    /** 节点签字动作（Task 6）：通过=签字，驳回留痕（状态流转仍由业务端点驱动） */
    private static final String APPROVE = "APPROVE";
    private static final String REJECT = "REJECT";
    /** 节点 → 授权角色（设计文档 §7/§10：安装部单角色、维保部经理 LEADER/ADMIN、接收工程师/查验人 WORKER） */
    private static final Map<String, Set<String>> NODE_ROLES = Map.of(
            "INSTALLER_HANDOVER", Set.of("INSTALLER"),
            "MAINTENANCE_ASSIGN", Set.of("LEADER", "ADMIN", "SYS_ADMIN"),
            "MAINTENANCE_CONFIRM", Set.of("LEADER", "ADMIN", "SYS_ADMIN"),
            "SITE_INSPECT", Set.of("WORKER", "LEADER", "ADMIN", "SYS_ADMIN"));

    private final HandoverOrderMapper orderMapper;
    private final HandoverCheckMapper checkMapper;
    private final HandoverCheckItemMapper checkItemMapper;
    private final HandoverRectificationMapper rectificationMapper;
    private final HandoverSignMapper signMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchService dispatchService;

    public HandoverService(HandoverOrderMapper orderMapper,
                           HandoverCheckMapper checkMapper,
                           HandoverCheckItemMapper checkItemMapper,
                           HandoverRectificationMapper rectificationMapper,
                           HandoverSignMapper signMapper,
                           ElevatorMapper elevatorMapper,
                           EmployeeMapper employeeMapper,
                           WorkOrderMapper workOrderMapper,
                           DispatchService dispatchService) {
        this.orderMapper = orderMapper;
        this.checkMapper = checkMapper;
        this.checkItemMapper = checkItemMapper;
        this.rectificationMapper = rectificationMapper;
        this.signMapper = signMapper; // Task 6 signNode 用
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchService = dispatchService;
    }

    /** 安装部发起移交：创建移交单并直接进入 DOC_SUBMITTING（INIT 仅作枚举默认值，无独立「开始」动作） */
    public HandoverOrder init(Map<String, Object> body) {
        String projectName = str(body, "projectName");
        if (blank(projectName)) {
            throw new BizException(422, "项目名称不能为空");
        }
        HandoverOrder o = new HandoverOrder();
        o.id = Ids.next("ho");
        o.handoverNo = Ids.next("ho");
        o.createdAt = LocalDateTime.now();
        o.status = DOC_SUBMITTING;
        o.projectName = projectName.trim();
        o.elevatorId = trimToNull(str(body, "elevatorId"));
        o.supervisionPassDate = dateOrNull(body == null ? null : body.get("supervisionPassDate"));
        o.initiatorId = trimToNull(str(body, "initiatorId"));
        o.initiatorName = trimToNull(str(body, "initiatorName"));
        // 电梯信息快照（供 Task 5 archive() 归档建电梯用）
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
        o.productNo = trimToNull(str(body, "productNo"));
        o.driveMode = trimToNull(str(body, "driveMode"));
        o.ratedLoad = intOrNull(body == null ? null : body.get("ratedLoad"));
        o.ratedLoadUnit = trimToNull(str(body, "ratedLoadUnit"));
        o.ratedSpeed = decimalOrNull(body == null ? null : body.get("ratedSpeed"));
        o.ratedSpeedUnit = trimToNull(str(body, "ratedSpeedUnit"));
        o.stationsDoors = trimToNull(str(body, "stationsDoors"));
        o.workTypeCode = trimToNull(str(body, "workTypeCode"));
        orderMapper.insert(o);
        return o;
    }

    /** 提交 3 必传资料（监督检验报告/维保说明书/整机质量证明），缺一 422；齐全则转 INSPECTING */
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
        o.status = INSPECTING;
        orderMapper.updateById(o);
        return o;
    }

    /** 维保部经理分配接收工程师（receiver_id/receiver_name），状态不变（仍 INSPECTING） */
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

    /** 提交现场查验结果：安全装置/制动器任一 FAIL → RECTIFYING，否则 → PENDING_CONFIRM */
    public HandoverOrder submitInspect(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, INSPECTING, "提交查验");

        HandoverCheck check = new HandoverCheck();
        check.id = Ids.next("hc");
        check.handoverId = o.id;
        check.checkerId = trimToNull(str(body, "checkerId"));
        check.checkerName = trimToNull(str(body, "checkerName"));
        check.remark = trimToNull(str(body, "remark"));
        check.checkedAt = LocalDateTime.now();
        checkMapper.insert(check);

        boolean forceRectify = false;
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : listOfMaps(body == null ? null : body.get("items"))) {
            String type = trimToNull(str(item, "itemType"));
            if (type == null) {
                continue;
            }
            String result = trimToNull(str(item, "result"));
            if (result == null) {
                result = "NA";
            }
            if (RECTIFY_ITEM_TYPES.contains(type) && FAIL.equals(result)) {
                forceRectify = true;
            }
            insertItem(check.id, type, result, str(item, "remark"));
            seen.add(type);
        }
        // 默认补齐缺失的 6 项为 NA，保证每单查验清单完整
        for (String type : ITEM_TYPES) {
            if (!seen.contains(type)) {
                insertItem(check.id, type, "NA", null);
            }
        }

        o.status = forceRectify ? RECTIFYING : PENDING_CONFIRM;
        orderMapper.updateById(o);
        return o;
    }

    /** 安装部上传整改前后照片，状态不变（仍 RECTIFYING） */
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

    /** 复验：通过 → PENDING_CONFIRM；不通过 → 保持 RECTIFYING */
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

    /** 维保部经理确认：PENDING_CONFIRM → ARCHIVED，记录确认人/时间。
     *  确认=归档一步到位：状态流转后随即调用 archive() 执行建电梯/责任人/首保工单副作用，整体一个事务。 */
    @Transactional(rollbackFor = Exception.class)
    public HandoverOrder confirm(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, PENDING_CONFIRM, "确认");
        o.confirmedById = trimToNull(str(body, "confirmedById"));
        o.confirmedByName = trimToNull(str(body, "confirmedByName"));
        o.confirmedAt = LocalDateTime.now();
        o.status = ARCHIVED;
        orderMapper.updateById(o);
        return archive(o.id);
    }

    /**
     * 归档副作用（Task 5 前半）：把移交单电梯信息快照建为电梯档案（archiveSource=1）、
     * 责任人=接收工程师（按 receiverId 查 sys_employee 的 platformId；查不到则只记姓名不阻断）、
     * 并预建首保（HM）工单（sourceHandoverId 回指移交单，planTime=归档日+15 自然日）。
     * 幂等：已归档（elevatorId 非空）直接返回，不重复建电梯/首保工单。
     */
    @Transactional(rollbackFor = Exception.class)
    public HandoverOrder archive(String handoverId) {
        HandoverOrder order = requireOrder(handoverId);
        if (order.elevatorId != null) {
            return order; // 已归档，幂等守卫
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
        el.productNo = order.productNo;
        el.driveMode = order.driveMode;
        el.ratedLoad = order.ratedLoad;
        el.ratedLoadUnit = order.ratedLoadUnit;
        el.ratedSpeed = order.ratedSpeed;
        el.ratedSpeedUnit = order.ratedSpeedUnit;
        el.stationsDoors = order.stationsDoors;
        el.workTypeCode = order.workTypeCode;
        el.projectName = order.projectName;
        el.supervisionPassDate = order.supervisionPassDate;
        el.archiveSource = 1;
        el.handoverAt = LocalDateTime.now();
        // 责任人=接收工程师；配合人安装移交时未定，留空
        el.workerName = order.receiverName;
        Employee receiver = order.receiverId == null ? null : employeeMapper.selectById(order.receiverId);
        el.workerPlatformId = receiver == null ? null : receiver.platformId;
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

    /**
     * 移交节点账号签字（Task 6，设计文档 §7）：内部账号「节点通过即签字」——actor 取登录人，
     * 账号名自动填写；只有该节点授权角色可签，否则 1403（防伪造他人节点确认）。
     * 只留痕不改状态机：节点动作与状态流转仍由各自业务端点驱动（submitDocs/assignReceiver/inspect/confirm）。
     */
    @Transactional(rollbackFor = Exception.class)
    public HandoverSign signNode(String handoverId, Map<String, Object> body, String actorEmpId) {
        HandoverOrder order = requireOrder(handoverId);
        String nodeType = str(body, "nodeType") == null ? "" : str(body, "nodeType").trim();
        Set<String> allowedRoles = NODE_ROLES.get(nodeType);
        if (allowedRoles == null) {
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
        if (!allowedRoles.contains(actor.role)) {
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

    /** 管理端列表（Task 14）：按状态过滤，发起时间倒序；内存再过滤一次防谓词漏条件 */
    public Map<String, Object> list(Map<String, String> query) {
        String status = query == null ? null : query.get("status");
        List<HandoverOrder> all = orderMapper.selectList(new LambdaQueryWrapper<HandoverOrder>()
                .orderByDesc(HandoverOrder::getCreatedAt));
        List<HandoverOrder> filtered = (all == null ? List.<HandoverOrder>of() : all).stream()
                .filter(o -> status == null || status.isBlank() || status.equals(o.status))
                .sorted(java.util.Comparator.comparing(
                        (HandoverOrder o) -> o.createdAt == null ? LocalDateTime.MIN : o.createdAt).reversed())
                .toList();
        return Paginate.of(filtered, query == null ? Map.of() : query);
    }

    /**
     * 详情（管理端 Task 14 / 小程序详情页共用）：单据字段保持顶层不变，另加
     * checks（查验批次+items）、rectifications、signs 三个关联数组，避免前端再打多个接口。
     */
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

    /** 移交单详情，不存在 1404 */
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

    private void insertItem(String checkId, String itemType, String result, String remark) {
        HandoverCheckItem it = new HandoverCheckItem();
        it.id = Ids.next("hi");
        it.checkId = checkId;
        it.itemType = itemType;
        it.result = result;
        it.remark = trimToNull(remark);
        checkItemMapper.insert(it);
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
