package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.HandoverArchive;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.mapper.HandoverArchiveMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C-12：GB/T 10060—2023 74 项移交检查清单。
 * 判定照 7.2/7.3（重要项任一不合格即不合格）；不合格项按 item_no 1:1 自动生成整改清单（不可增删）；
 * 逐条复验（全部 PASS 才回待确认）；☆项置 NA 须经理审批；归档 SHA-256 + 4 年留存。
 * 本清单为内部移交把关，不替代监督检验（B1）。
 */
@Service
public class HandoverChecklistService {

    public static final String QUALIFIED = "QUALIFIED";
    public static final String RECTIFY = "RECTIFY";
    public static final String DISQUALIFIED = "DISQUALIFIED";
    public static final String CONDITIONAL = "CONDITIONAL";
    private static final String INSPECTING = "INSPECTING";
    private static final String RECTIFYING = "RECTIFYING";
    private static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";
    private static final String NA = "NA";

    private final HandoverOrderMapper orderMapper;
    private final HandoverCheckMapper checkMapper;
    private final HandoverCheckItemMapper itemMapper;
    private final HandoverRectificationMapper rectMapper;
    private final HandoverSignMapper signMapper;
    private final HandoverArchiveMapper archiveMapper;

    public HandoverChecklistService(HandoverOrderMapper orderMapper, HandoverCheckMapper checkMapper,
                                    HandoverCheckItemMapper itemMapper, HandoverRectificationMapper rectMapper,
                                    HandoverSignMapper signMapper, HandoverArchiveMapper archiveMapper) {
        this.orderMapper = orderMapper;
        this.checkMapper = checkMapper;
        this.itemMapper = itemMapper;
        this.rectMapper = rectMapper;
        this.signMapper = signMapper;
        this.archiveMapper = archiveMapper;
    }

    /** 进入 INSPECTING 时生成 74 项草稿（不随查验人变动重建）。 */
    @Transactional
    public HandoverCheck generateChecklist(String handoverId) {
        HandoverOrder o = requireOrder(handoverId);
        HandoverCheck existing = latestCheck(handoverId);
        if (existing != null) {
            return existing; // 已有清单任意状态可读（整改/待确认/归档态查看）
        }
        if (!INSPECTING.equals(o.status)) {
            throw new BizException(422, "仅查验中可生成检查清单");
        }
        HandoverCheck check = new HandoverCheck();
        check.id = Ids.next("hc");
        check.handoverId = handoverId;
        check.checkedAt = LocalDateTime.now();
        check.totalItems = 74;
        checkMapper.insert(check);
        for (HandoverChecklistTemplate.Item t : HandoverChecklistTemplate.items()) {
            HandoverCheckItem it = new HandoverCheckItem();
            it.id = Ids.next("hi");
            it.checkId = check.id;
            it.itemNo = t.no();
            it.itemGroup = t.group();
            it.itemType = t.group(); // 兼容列 item_type NOT NULL（V27），新单以 G01—G13 项类填充
            it.clauseNo = t.clause();
            it.itemName = t.name();
            it.requirement = t.requirement();
            it.important = t.important();
            itemMapper.insert(it);
        }
        return check;
    }

    public Map<String, Object> getChecklist(String handoverId) {
        HandoverCheck check = generateChecklist(handoverId);
        List<HandoverCheckItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<HandoverCheckItem>().eq(HandoverCheckItem::getCheckId, check.id)
                        .orderByAsc(HandoverCheckItem::getItemNo));
        Map<String, Object> out = new HashMap<>();
        out.put("check", check);
        out.put("items", items);
        return out;
    }

    /** 提交查验：74 项齐备校验 + 判定 + 1:1 整改清单（同一事务）。 */
    @Transactional
    public HandoverOrder submitChecklist(String handoverId, List<Map<String, Object>> submitted, Map<String, Object> cond) {
        HandoverOrder o = requireOrder(handoverId);
        if (!INSPECTING.equals(o.status)) {
            throw new BizException(422, "仅查验中可提交");
        }
        HandoverCheck check = generateChecklist(handoverId);
        List<HandoverCheckItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<HandoverCheckItem>().eq(HandoverCheckItem::getCheckId, check.id));
        Map<Integer, HandoverCheckItem> byNo = new HashMap<>();
        for (HandoverCheckItem it : items) byNo.put(it.itemNo, it);

        Map<Integer, Map<String, Object>> sub = new HashMap<>();
        for (Map<String, Object> s : submitted) {
            Object no = s.get("itemNo");
            if (no != null) sub.put(Integer.parseInt(String.valueOf(no)), s);
        }
        boolean naImportantPending = false;
        int pass = 0, fail = 0, na = 0, impFail = 0;
        List<HandoverCheckItem> fails = new ArrayList<>();
        for (HandoverCheckItem it : items) {
            Map<String, Object> s = sub.get(it.itemNo);
            String result = s == null ? null : str(s, "result");
            if (result == null || result.isBlank()) {
                throw new BizException(422, "第 " + it.itemNo + " 项未填结果");
            }
            it.result = result;
            it.measuredValue = s == null ? null : str(s, "measuredValue");
            it.failDesc = s == null ? null : str(s, "failDesc");
            it.naReason = s == null ? null : str(s, "naReason");
            it.naNote = s == null ? null : str(s, "naNote");
            it.photoFileIds = s == null ? null : str(s, "photoFileIds");
            if (FAIL.equals(result)) {
                if (blank(it.failDesc)) throw new BizException(422, "第 " + it.itemNo + " 项不合格须填描述");
                boolean needPhoto = Boolean.TRUE.equals(it.important) || HandoverChecklistTemplate.isTestItem(it.itemNo);
                if (needPhoto && blank(it.photoFileIds)) throw new BizException(422, "第 " + it.itemNo + " 项不合格须附照片");
                fail++;
                if (Boolean.TRUE.equals(it.important)) impFail++;
                fails.add(it);
            } else if (NA.equals(result)) {
                if (blank(it.naReason)) throw new BizException(422, "第 " + it.itemNo + " 项不适用须选原因码");
                if (Boolean.TRUE.equals(it.important)) naImportantPending = true;
                na++;
            } else if (PASS.equals(result)) {
                if (HandoverChecklistTemplate.isTestItem(it.itemNo) && blank(it.measuredValue)) {
                    throw new BizException(422, "第 " + it.itemNo + " 项试验须填实测值");
                }
                pass++;
            } else {
                throw new BizException(422, "第 " + it.itemNo + " 项结果须 PASS/FAIL/NA");
            }
            itemMapper.updateById(it);
        }
        if (naImportantPending && !hasNaApproval(handoverId)) {
            throw new BizException(422, "重要项置不适用须维保部经理审批");
        }
        String conclusion = judge(impFail, fail - impFail, cond);
        check.passCount = pass;
        check.failCount = fail;
        check.naCount = na;
        check.importantFailCount = impFail;
        check.conclusion = conclusion;
        check.snapshotJson = snapshot(check, items);
        checkMapper.updateById(check);

        if (RECTIFY.equals(conclusion) || DISQUALIFIED.equals(conclusion)) {
            for (HandoverCheckItem f : fails) {
                HandoverRectification r = new HandoverRectification();
                r.id = Ids.next("hr");
                r.handoverId = handoverId;
                r.itemNo = f.itemNo;
                r.itemType = f.itemGroup;
                r.respParty = "安装单位";
                r.dueDate = LocalDate.now().plusDays(7).toString();
                r.source = "INSPECT";
                r.voided = false;
                r.beforePhotoFileId = f.photoFileIds;
                rectMapper.insert(r);
            }
            o.status = RECTIFYING;
        } else {
            o.status = PENDING_CONFIRM;
        }
        orderMapper.updateById(o);
        return o;
    }

    /** 7.2/7.3 判定矩阵（5 路径）。 */
    public static String judge(int impFail, int genFail, Map<String, Object> cond) {
        if (impFail == 0 && genFail == 0) return QUALIFIED;
        if (impFail >= 1) return DISQUALIFIED;
        if (genFail > 8) return DISQUALIFIED;
        boolean conditional = cond != null
                && Boolean.TRUE.equals(cond.get("siteLimited"))
                && Boolean.TRUE.equals(cond.get("tripartiteAgreed"))
                && !blank(str(cond, "controlMeasures"));
        if (genFail >= 1 && genFail <= 3 && conditional) return CONDITIONAL;
        return RECTIFY;
    }

    /** 安装部逐条上传整改措施/后照片。 */
    @Transactional
    public HandoverRectification rectifyItem(String handoverId, String rectId, Map<String, Object> body) {
        HandoverRectification r = requireRect(handoverId, rectId);
        r.rectMeasure = str(body, "rectMeasure");
        r.afterPhotoFileId = str(body, "afterPhotoFileId");
        r.rectifierName = str(body, "rectifierName");
        r.rectifiedAt = LocalDateTime.now();
        rectMapper.updateById(r);
        return r;
    }

    /** 逐条复验；全部 PASS 才回 PENDING_CONFIRM（M6）。 */
    @Transactional
    public HandoverOrder recheckItem(String handoverId, String rectId, Map<String, Object> body) {
        HandoverOrder o = requireOrder(handoverId);
        if (!RECTIFYING.equals(o.status)) throw new BizException(422, "仅整改中可复验");
        HandoverRectification r = requireRect(handoverId, rectId);
        String result = str(body, "result");
        if (!PASS.equals(result) && !FAIL.equals(result)) throw new BizException(422, "复验结果须 PASS/FAIL");
        r.recheckResult = result;
        r.recheckerId = str(body, "recheckerId");
        r.recheckerName = str(body, "recheckerName");
        r.recheckedAt = LocalDateTime.now();
        rectMapper.updateById(r);
        List<HandoverRectification> all = rectMapper.selectList(
                new LambdaQueryWrapper<HandoverRectification>().eq(HandoverRectification::getHandoverId, handoverId)
                        .eq(HandoverRectification::getVoided, false));
        boolean allPass = !all.isEmpty() && all.stream().allMatch(x -> PASS.equals(x.recheckResult));
        o.status = allPass ? PENDING_CONFIRM : RECTIFYING;
        orderMapper.updateById(o);
        return o;
    }

    /** ☆项置 NA 的经理审批（写 handover_sign 节点 NA_APPROVE）。 */
    @Transactional
    public HandoverSign naApprove(String handoverId, Map<String, Object> body) {
        HandoverSign s = new HandoverSign();
        s.id = Ids.next("hs");
        s.handoverId = handoverId;
        s.nodeType = "NA_APPROVE";
        s.actorId = str(body, "approverId");
        s.actorName = str(body, "approverName");
        s.action = "APPROVE";
        s.at = LocalDateTime.now();
        signMapper.insert(s);
        return s;
    }

    private boolean hasNaApproval(String handoverId) {
        Long n = signMapper.selectCount(new LambdaQueryWrapper<HandoverSign>()
                .eq(HandoverSign::getHandoverId, handoverId).eq(HandoverSign::getNodeType, "NA_APPROVE")
                .eq(HandoverSign::getAction, "APPROVE"));
        return n != null && n > 0;
    }

    /** 归档目录 + SHA-256（幂等）。 */
    @Transactional
    public List<HandoverArchive> archive(String handoverId) {
        List<HandoverArchive> existing = archiveMapper.selectList(
                new LambdaQueryWrapper<HandoverArchive>().eq(HandoverArchive::getHandoverId, handoverId));
        if (!existing.isEmpty()) return existing;
        HandoverCheck check = latestCheck(handoverId);
        List<HandoverArchive> out = new ArrayList<>();
        String[][] entries = {
                {"01", "CHECKLIST", "电梯安装移交维保检查清单.pdf"},
                {"02", "RECTIFICATION", "不合格项整改清单.pdf"},
                {"03", "DOCS", "移交资料 D1—D20"},
                {"04", "PHOTOS", "逐项照片+定角照片"},
                {"05", "CONFIRMATION", "交接确认单"},
                {"06", "FIRST_ORDER", "首保工单关联记录"},
                {"07", "PENDING_DOCS", "待补件清单"}
        };
        int seq = 1;
        for (String[] e : entries) {
            HandoverArchive a = new HandoverArchive();
            a.id = Ids.next("ha");
            a.handoverId = handoverId;
            a.category = e[1];
            a.seq = seq++;
            a.sha256 = sha256(handoverId + "/" + e[0] + "/" + (check == null ? "" : check.snapshotJson));
            a.archivedAt = LocalDateTime.now();
            archiveMapper.insert(a);
            out.add(a);
        }
        return out;
    }

    /** 一梯一档：按电梯 id 取其最近移交单的归档目录（电梯档案抽屉用）。 */
    public List<HandoverArchive> archiveByElevator(String elevatorId) {
        List<HandoverOrder> orders = orderMapper.selectList(
                new LambdaQueryWrapper<HandoverOrder>().eq(HandoverOrder::getElevatorId, elevatorId)
                        .orderByDesc(HandoverOrder::getCreatedAt));
        if (orders.isEmpty()) {
            return new ArrayList<>();
        }
        return archive(orders.get(0).id);
    }

    private HandoverCheck latestCheck(String handoverId) {
        List<HandoverCheck> checks = checkMapper.selectList(
                new LambdaQueryWrapper<HandoverCheck>().eq(HandoverCheck::getHandoverId, handoverId)
                        .orderByDesc(HandoverCheck::getCheckedAt));
        return checks.isEmpty() ? null : checks.get(0);
    }

    private HandoverRectification requireRect(String handoverId, String rectId) {
        HandoverRectification r = rectMapper.selectById(rectId);
        if (r == null || !handoverId.equals(r.handoverId)) throw new BizException(404, "整改记录不存在");
        return r;
    }

    private HandoverOrder requireOrder(String id) {
        HandoverOrder o = orderMapper.selectById(id);
        if (o == null) throw new BizException(404, "移交单不存在");
        return o;
    }

    private static String snapshot(HandoverCheck c, List<HandoverCheckItem> items) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"conclusion\":\"").append(c.conclusion).append("\",\"items\":[");
        for (HandoverCheckItem it : items) {
            sb.append("{\"no\":").append(it.itemNo).append(",\"result\":\"").append(it.result).append("\"},");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}