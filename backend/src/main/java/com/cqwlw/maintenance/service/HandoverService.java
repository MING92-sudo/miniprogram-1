package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
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

    private final HandoverOrderMapper orderMapper;
    private final HandoverCheckMapper checkMapper;
    private final HandoverCheckItemMapper checkItemMapper;
    private final HandoverRectificationMapper rectificationMapper;
    private final HandoverSignMapper signMapper;

    public HandoverService(HandoverOrderMapper orderMapper,
                           HandoverCheckMapper checkMapper,
                           HandoverCheckItemMapper checkItemMapper,
                           HandoverRectificationMapper rectificationMapper,
                           HandoverSignMapper signMapper) {
        this.orderMapper = orderMapper;
        this.checkMapper = checkMapper;
        this.checkItemMapper = checkItemMapper;
        this.rectificationMapper = rectificationMapper;
        this.signMapper = signMapper; // Task 6 signNode 用
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

    /** 维保部经理确认：PENDING_CONFIRM → ARCHIVED，记录确认人/时间。纯状态流转，无电梯/首保副作用（Task 5 archive()） */
    public HandoverOrder confirm(String id, Map<String, Object> body) {
        HandoverOrder o = requireOrder(id);
        requireStatus(o, PENDING_CONFIRM, "确认");
        o.confirmedById = trimToNull(str(body, "confirmedById"));
        o.confirmedByName = trimToNull(str(body, "confirmedByName"));
        o.confirmedAt = LocalDateTime.now();
        o.status = ARCHIVED;
        orderMapper.updateById(o);
        return o;
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
