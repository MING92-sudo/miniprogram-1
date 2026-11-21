package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.common.Paginate;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.ExternalFilingDoc;
import com.cqwlw.maintenance.entity.ExternalFilingOrder;
import com.cqwlw.maintenance.entity.ExternalTakeoverCheck;
import com.cqwlw.maintenance.entity.ExternalTakeoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverConfirmation;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.ExternalFilingDocMapper;
import com.cqwlw.maintenance.mapper.ExternalFilingOrderMapper;
import com.cqwlw.maintenance.mapper.ExternalTakeoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.ExternalTakeoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverConfirmationMapper;
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
import java.util.Set;

/**
 * 外接建档单（W2，Task 7）状态机：合同前置 → 资料收集（允许缺件待补办）→ 接管查验（不合格不阻塞、责任归属必填）
 * → 交接确认（双方签字）→ 归档（建电梯 archive_source=2 + 首保 planTime=归档日+3 日）。
 * 5 态见 {@code docs/superpowers/specs/...-design.md §6.2}；角色门禁留待 Task 8。
 */
@Service
public class ExternalFilingService {

    private static final String INIT = "INIT";
    private static final String DOC_COLLECTING = "DOC_COLLECTING";
    private static final String TAKEOVER_CHECKING = "TAKEOVER_CHECKING";
    private static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    private static final String ARCHIVED = "ARCHIVED";

    /** 建档资料类别（V29 迁移注释同源）；OTHER 兜底 */
    static final Set<String> DOC_TYPES = Set.of(
            "USE_REG_CERT", "INSPECTION_REPORT", "MAINTENANCE_RECORD",
            "MAINTENANCE_MANUAL", "ELEVATOR_DRAWING", "OTHER");
    /** 接管查验项（与移交现场查验 6 类同口径，便于两端对账） */
    private static final List<String> ITEM_TYPES = List.of(
            "RUNNING", "SAFETY_DEVICE", "BRAKE", "EMERGENCY_OPERATION", "MACHINE_ROOM_SHAFT", "ACCESSORIES");
    /** 不合格项责任归属（设计文档 §6.2：不阻塞建档，但必须写清由谁负责） */
    static final Set<String> RESPONSIBILITIES = Set.of("PREV_MAINT", "USE_UNIT", "OURS_AFTER_TAKEOVER");
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";
    private static final String NA = "NA";
    /** 外接建档首保：接收日（归档日）+3 自然日（移交是 +15，见 HandoverService.archive） */
    private static final int FIRST_ORDER_DELAY_DAYS = 3;

    private final ExternalFilingOrderMapper orderMapper;
    private final ExternalFilingDocMapper docMapper;
    private final ExternalTakeoverCheckMapper checkMapper;
    private final ExternalTakeoverCheckItemMapper checkItemMapper;
    private final HandoverConfirmationMapper confirmationMapper;
    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchService dispatchService;
    private final ContractService contractService;

    public ExternalFilingService(ExternalFilingOrderMapper orderMapper,
                                 ExternalFilingDocMapper docMapper,
                                 ExternalTakeoverCheckMapper checkMapper,
                                 ExternalTakeoverCheckItemMapper checkItemMapper,
                                 HandoverConfirmationMapper confirmationMapper,
                                 ElevatorMapper elevatorMapper,
                                 WorkOrderMapper workOrderMapper,
                                 DispatchService dispatchService,
                                 ContractService contractService) {
        this.orderMapper = orderMapper;
        this.docMapper = docMapper;
        this.checkMapper = checkMapper;
        this.checkItemMapper = checkItemMapper;
        this.confirmationMapper = confirmationMapper;
        this.elevatorMapper = elevatorMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchService = dispatchService;
        this.contractService = contractService;
    }

    /**
     * 发起外接建档：必须关联已生效合同（起始日 ≤ 今天、未过期、属同一使用单位）；
     * 同一电梯已有其它生效合同 → 422 冲突（AGENTS §3 不产生半成品状态）。
     * 直接进入 DOC_COLLECTING（INIT 仅作枚举默认值）。
     */
    public ExternalFilingOrder init(Map<String, Object> body) {
        String useUnitId = trimToNull(str(body, "useUnitId"));
        if (useUnitId == null) {
            throw new BizException(422, "请选择使用单位");
        }
        String contractId = trimToNull(str(body, "contractId"));
        if (contractId == null) {
            throw new BizException(422, "外接建档必须关联已生效合同");
        }
        MaintenanceContract contract = contractService.get(contractId);
        requireActiveContract(contract);
        if (!useUnitId.equals(contract.useUnitId)) {
            throw new BizException(422, "所选合同不属于该使用单位");
        }
        String elevatorCode = trimToNull(str(body, "elevatorCode"));
        requireNoOtherActiveContract(elevatorCode, contractId);

        ExternalFilingOrder o = new ExternalFilingOrder();
        o.id = Ids.next("ef");
        o.filingNo = Ids.next("ef");
        o.createdAt = LocalDateTime.now();
        o.status = DOC_COLLECTING;
        o.contractId = contractId;
        o.useUnitId = useUnitId;
        o.initiatorId = trimToNull(str(body, "initiatorId"));
        o.initiatorName = trimToNull(str(body, "initiatorName"));
        o.projectName = trimToNull(str(body, "projectName"));
        o.supervisionPassDate = dateOrNull(body == null ? null : body.get("supervisionPassDate"));
        o.elevatorCode = elevatorCode;
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
        orderMapper.insert(o);
        return o;
    }

    /**
     * 提交建档资料：可标 missing=true 表示缺件待补办（不阻断，后续在交接确认单留遗留问题）；
     * 未标缺件的资料必须带 fileId。提交后进入 TAKEOVER_CHECKING。
     */
    public ExternalFilingOrder submitDocs(String id, Map<String, Object> body) {
        ExternalFilingOrder o = requireFiling(id);
        requireStatus(o, DOC_COLLECTING, "提交建档资料");
        List<Map<String, Object>> docs = listOfMaps(body == null ? null : body.get("docs"));
        if (docs.isEmpty()) {
            throw new BizException(422, "请至少提交一项建档资料");
        }
        for (Map<String, Object> d : docs) {
            String docType = trimToNull(str(d, "docType"));
            if (docType == null || !DOC_TYPES.contains(docType)) {
                throw new BizException(422, "资料类别不合法：" + (docType == null ? "(空)" : docType));
            }
            boolean missing = truthy(d.get("missing"));
            String fileId = trimToNull(str(d, "fileId"));
            if (!missing && fileId == null) {
                throw new BizException(422, "资料「" + docType + "」未上传且未标记缺件");
            }
            ExternalFilingDoc doc = new ExternalFilingDoc();
            doc.id = Ids.next("fd");
            doc.filingId = o.id;
            doc.docType = docType;
            doc.fileId = fileId;
            doc.missingFlag = missing;
            doc.remark = trimToNull(str(d, "remark"));
            docMapper.insert(doc);
        }
        o.status = TAKEOVER_CHECKING;
        orderMapper.updateById(o);
        return o;
    }

    /**
     * 提交接管查验：不合格项**不阻塞**建档（进入 PENDING_CONFIRM），但必须填写责任归属。
     * 未提交的查验项自动补 NA，保证每单清单完整（与移交 submitInspect 同口径）。
     */
    public ExternalFilingOrder submitTakeoverCheck(String id, Map<String, Object> body) {
        ExternalFilingOrder o = requireFiling(id);
        requireStatus(o, TAKEOVER_CHECKING, "提交接管查验");

        ExternalTakeoverCheck check = new ExternalTakeoverCheck();
        check.id = Ids.next("tc");
        check.filingId = o.id;
        check.checkerId = trimToNull(str(body, "checkerId"));
        check.checkerName = trimToNull(str(body, "checkerName"));
        check.remark = trimToNull(str(body, "remark"));
        check.checkedAt = LocalDateTime.now();
        checkMapper.insert(check);

        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : listOfMaps(body == null ? null : body.get("items"))) {
            String type = trimToNull(str(item, "itemType"));
            if (type == null) {
                continue;
            }
            String result = trimToNull(str(item, "result"));
            if (result == null) {
                result = NA;
            }
            String responsibility = trimToNull(str(item, "responsibility"));
            if (FAIL.equals(result)) {
                if (responsibility == null) {
                    throw new BizException(422, "不合格项「" + type + "」必须填写责任归属");
                }
                if (!RESPONSIBILITIES.contains(responsibility)) {
                    throw new BizException(422, "责任归属不合法：" + responsibility);
                }
            } else {
                responsibility = null; // 合格/不适用项不带责任归属，避免脏数据
            }
            insertItem(check.id, type, result, responsibility, str(item, "remark"));
            seen.add(type);
        }
        for (String type : ITEM_TYPES) {
            if (!seen.contains(type)) {
                insertItem(check.id, type, NA, null, null);
            }
        }
        o.status = PENDING_CONFIRM;
        orderMapper.updateById(o);
        return o;
    }

    /**
     * 交接确认（PENDING_CONFIRM → ARCHIVED）：我方账号「节点通过即签字」+ 对方手写签名必备；
     * 确认与归档一步到位，整体一个事务（与移交 confirm 同口径）。
     */
    @Transactional(rollbackFor = Exception.class)
    public HandoverConfirmation confirm(String id, Map<String, Object> body) {
        ExternalFilingOrder o = requireFiling(id);
        requireStatus(o, PENDING_CONFIRM, "交接确认");
        String counterpartName = trimToNull(str(body, "counterpartSignName"));
        if (counterpartName == null) {
            throw new BizException(422, "请填写对方签字人姓名");
        }
        String counterpartSign = trimToNull(str(body, "counterpartSignFileId"));
        if (counterpartSign == null) {
            throw new BizException(422, "请上传对方手写签名");
        }
        LocalDateTime now = LocalDateTime.now();
        HandoverConfirmation conf = new HandoverConfirmation();
        conf.id = Ids.next("hcn");
        conf.filingId = o.id;
        conf.keyToolCheck = trimToNull(str(body, "keyToolCheck"));
        conf.leftoverIssue = trimToNull(str(body, "leftoverIssue"));
        conf.ourSignFileId = trimToNull(str(body, "ourSignFileId"));
        conf.counterpartSignName = counterpartName;
        conf.counterpartSignFileId = counterpartSign;
        conf.confirmedById = trimToNull(str(body, "confirmedById"));
        conf.confirmedByName = trimToNull(str(body, "confirmedByName"));
        conf.at = now;
        confirmationMapper.insert(conf);

        o.confirmedById = conf.confirmedById;
        o.confirmedByName = conf.confirmedByName;
        o.confirmedAt = now;
        o.status = ARCHIVED;
        orderMapper.updateById(o);
        archive(o.id);
        return conf;
    }

    /**
     * 归档副作用：把外接建档单的电梯信息快照建为电梯档案（archiveSource=2）、
     * 预建首保（HM）工单（sourceFilingId 回指本单，planTime=归档日+3 自然日）。
     * 幂等：已归档（elevatorId 非空）直接返回，不重复建电梯/首保工单。
     * 责任人不在建档时指定（外接梯由维保经理后续绑定派单），故 worker 留空。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExternalFilingOrder archive(String filingId) {
        ExternalFilingOrder o = requireFiling(filingId);
        if (o.elevatorId != null) {
            return o;
        }
        Elevator el = new Elevator();
        el.id = Ids.next("el");
        el.elevatorCode = o.elevatorCode;
        el.elevatorName = o.elevatorName;
        el.category = o.category;
        el.location = o.location;
        el.regCode = o.regCode;
        el.deviceCode = o.deviceCode;
        el.insideNumber = o.insideNumber;
        el.model = o.model;
        el.brand = o.brand;
        el.manufacturer = o.manufacturer;
        // 出厂编号（原单据 productNo，V59 统一更名 factoryNumber）
        el.factoryNumber = o.factoryNumber;
        el.driveMode = o.driveMode;
        el.ratedLoad = o.ratedLoad;
        el.ratedLoadUnit = o.ratedLoadUnit;
        el.ratedSpeed = o.ratedSpeed;
        el.ratedSpeedUnit = o.ratedSpeedUnit;
        el.stationsDoors = o.stationsDoors;
        el.workTypeCode = o.workTypeCode;
        el.projectName = o.projectName;
        el.supervisionPassDate = o.supervisionPassDate;
        el.archiveSource = 2;
        el.handoverAt = LocalDateTime.now();
        elevatorMapper.insert(el);
        o.elevatorId = el.id;
        orderMapper.updateById(o);
        WorkOrder first = dispatchService.precreateFirstOrder(el, LocalDate.now().plusDays(FIRST_ORDER_DELAY_DAYS));
        if (first != null) {
            first.sourceFilingId = o.id;
            workOrderMapper.updateById(first);
        }
        return o;
    }

    /** 管理端列表（Task 14）：按状态过滤，发起时间倒序 */
    public Map<String, Object> list(Map<String, String> query) {
        String status = query == null ? null : query.get("status");
        List<ExternalFilingOrder> all = orderMapper.selectList(new LambdaQueryWrapper<ExternalFilingOrder>()
                .orderByDesc(ExternalFilingOrder::getCreatedAt));
        List<ExternalFilingOrder> filtered = (all == null ? List.<ExternalFilingOrder>of() : all).stream()
                .filter(o -> status == null || status.isBlank() || status.equals(o.status))
                .sorted(java.util.Comparator.comparing(
                        (ExternalFilingOrder o) -> o.createdAt == null ? LocalDateTime.MIN : o.createdAt).reversed())
                .toList();
        return Paginate.of(filtered, query == null ? Map.of() : query);
    }

    /** 详情（Task 14 管理端）：单据字段保持顶层，另加 docs / takeoverCheckItems / confirmation */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detail(String id) {
        ExternalFilingOrder o = requireFiling(id);
        Map<String, Object> out = com.cqwlw.maintenance.util.JsonUtil.MAPPER.convertValue(o, Map.class);
        List<ExternalFilingDoc> docs = docMapper.selectList(new LambdaQueryWrapper<ExternalFilingDoc>()
                .eq(ExternalFilingDoc::getFilingId, id));
        out.put("docs", docs == null ? List.of() : docs);
        List<ExternalTakeoverCheck> checks = checkMapper.selectList(new LambdaQueryWrapper<ExternalTakeoverCheck>()
                .eq(ExternalTakeoverCheck::getFilingId, id).orderByDesc(ExternalTakeoverCheck::getCheckedAt));
        List<Map<String, Object>> checkViews = new ArrayList<>();
        for (ExternalTakeoverCheck c : checks == null ? List.<ExternalTakeoverCheck>of() : checks) {
            Map<String, Object> m = new LinkedHashMap<>(com.cqwlw.maintenance.util.JsonUtil.MAPPER.convertValue(c, Map.class));
            List<ExternalTakeoverCheckItem> items = checkItemMapper.selectList(new LambdaQueryWrapper<ExternalTakeoverCheckItem>()
                    .eq(ExternalTakeoverCheckItem::getCheckId, c.id));
            m.put("items", items == null ? List.of() : items);
            checkViews.add(m);
        }
        out.put("takeoverChecks", checkViews);
        List<HandoverConfirmation> confs = confirmationMapper.selectList(new LambdaQueryWrapper<HandoverConfirmation>()
                .eq(HandoverConfirmation::getFilingId, id));
        out.put("confirmation", confs == null || confs.isEmpty() ? null : confs.get(0));
        return out;
    }

    /** 外接建档单详情，不存在 1404 */
    public ExternalFilingOrder get(String id) {
        return requireFiling(id);
    }

    /** 合同必须生效且未过期：起始日 ≤ 今天 ≤ 终止日 */
    private void requireActiveContract(MaintenanceContract c) {
        if (!"ACTIVE".equals(c.status)) {
            throw new BizException(422, "合同未生效，不能发起外接建档");
        }
        LocalDate today = LocalDate.now();
        if (c.startDate == null || c.startDate.isAfter(today)) {
            throw new BizException(422, "合同起始日晚于建档日，不能发起外接建档");
        }
        if (c.endDate != null && c.endDate.isBefore(today)) {
            throw new BizException(422, "合同已过期，请先续签");
        }
    }

    /** 同梯多生效合同拦截：该电梯编码已建档且另有生效合同 → 422（AGENTS §3 不落半成品） */
    private void requireNoOtherActiveContract(String elevatorCode, String contractId) {
        if (elevatorCode == null) {
            return;
        }
        Elevator existing = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, elevatorCode)).stream().findFirst().orElse(null);
        if (existing == null) {
            return;
        }
        MaintenanceContract other = contractService.getActiveContractByElevator(existing.id);
        if (other != null && !contractId.equals(other.id)) {
            throw new BizException(422, "该电梯已有生效合同：" + other.contractNo);
        }
    }

    private ExternalFilingOrder requireFiling(String id) {
        ExternalFilingOrder o = orderMapper.selectById(id);
        if (o == null) {
            throw new BizException(1404, "外接建档单不存在");
        }
        return o;
    }

    private static void requireStatus(ExternalFilingOrder o, String expected, String action) {
        if (!expected.equals(o.status)) {
            throw new BizException(422, "当前状态不允许" + action);
        }
    }

    private void insertItem(String checkId, String itemType, String result,
                            String responsibility, String remark) {
        ExternalTakeoverCheckItem it = new ExternalTakeoverCheckItem();
        it.id = Ids.next("ti");
        it.checkId = checkId;
        it.itemType = itemType;
        it.result = result;
        it.responsibility = trimToNull(responsibility);
        it.remark = trimToNull(remark);
        checkItemMapper.insert(it);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 兼容布尔与 "true" 字符串（JSON 与表单两种来源） */
    private static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object v) {
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .toList();
        }
        return List.of();
    }
}
