package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ChecklistTemplate;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查项模板管理（docs/04 A.4.2，docs/09 二期）：
 * OFFICIAL 257 行只读（TSG 附件原文，改动须走模板修订流程并复测 docs/05 §10.5）；
 * CUSTOM（消防/防爆等按制造单位要求）可新建/编辑/启停，category_scope 匹配 elevator.special_type，
 * 未配置时 sync-status.templateMissing 计数（1006 语义）。
 */
@Service
public class AdminTemplateService {

    /** 自定义模板允许的特殊类别（TSG 第二条：消防/防爆电梯按制造单位要求配置） */
    public static final List<String> CUSTOM_SCOPES = List.of("消防电梯", "防爆电梯");

    private final ChecklistTemplateMapper templateMapper;

    public AdminTemplateService(ChecklistTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    public Map<String, Object> listTemplates(Map<String, String> q) {
        LambdaQueryWrapper<ChecklistTemplate> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("templateType"))) {
            w.eq(ChecklistTemplate::getTemplateType, q.get("templateType"));
        }
        if (notBlank(q.get("appendix"))) {
            w.eq(ChecklistTemplate::getAppendix, q.get("appendix"));
        }
        if (notBlank(q.get("categoryScope"))) {
            w.eq(ChecklistTemplate::getCategoryScope, q.get("categoryScope"));
        }
        if (notBlank(q.get("keyword"))) {
            String kw = q.get("keyword");
            w.and(x -> x.like(ChecklistTemplate::getName, kw)
                    .or().like(ChecklistTemplate::getItemCode, kw));
        }
        w.orderByAsc(ChecklistTemplate::getTemplateType)
                .orderByAsc(ChecklistTemplate::getAppendix)
                .orderByAsc(ChecklistTemplate::getSeq);
        List<ChecklistTemplate> all = templateMapper.selectList(w);

        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());

        List<Map<String, Object>> list = new ArrayList<>();
        for (ChecklistTemplate t : all.subList(from, to)) {
            list.add(row(t, true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    public Map<String, Object> createTemplate(Map<String, Object> body) {
        String name = str(body, "name");
        String scope = str(body, "categoryScope");
        if (name.isBlank()) {
            throw new BizException(422, "模板名称必填");
        }
        if (!CUSTOM_SCOPES.contains(scope)) {
            throw new BizException(422, "categoryScope 仅允许：" + String.join("、", CUSTOM_SCOPES));
        }
        String judgeType = str(body, "judgeType").isBlank() ? "QUALITATIVE" : str(body, "judgeType");
        if (!List.of("NUMERIC", "STANDARD", "MANUFACTURER", "QUALITATIVE").contains(judgeType)) {
            throw new BizException(422, "判定方式仅允许 NUMERIC/STANDARD/MANUFACTURER/QUALITATIVE");
        }
        ChecklistTemplate t = new ChecklistTemplate();
        t.templateType = "CUSTOM";
        t.categoryScope = scope;
        t.itemCode = "CT-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        t.seq = 0;
        t.name = name;
        t.judgeType = judgeType;
        t.isKey = Boolean.parseBoolean(str(body, "isKey"));
        t.photoRequired = Boolean.parseBoolean(str(body, "photoRequired"));
        t.enabled = true;
        t.payload = JsonUtil.write(customPayload(t, body));
        t.createdAt = TimeUtil.now();
        t.updatedAt = TimeUtil.now();
        templateMapper.insert(t);
        return row(t, true);
    }

    public Map<String, Object> updateTemplate(Long id, Map<String, Object> body) {
        ChecklistTemplate t = requireCustom(id);
        if (body.get("name") != null) {
            t.name = str(body, "name");
        }
        if (body.get("requirement") != null || body.get("valueMin") != null
                || body.get("valueMax") != null || body.get("valueUnit") != null
                || body.get("judgeType") != null) {
            t.judgeType = str(body, "judgeType").isBlank() ? t.judgeType : str(body, "judgeType");
            t.payload = JsonUtil.write(customPayload(t, body));
        }
        if (body.get("isKey") != null) {
            t.isKey = Boolean.parseBoolean(str(body, "isKey"));
        }
        if (body.get("photoRequired") != null) {
            t.photoRequired = Boolean.parseBoolean(str(body, "photoRequired"));
        }
        if (body.get("categoryScope") != null && !str(body, "categoryScope").isBlank()) {
            t.categoryScope = str(body, "categoryScope");
        }
        t.updatedAt = TimeUtil.now();
        templateMapper.updateById(t);
        return row(t, true);
    }

    public Map<String, Object> setEnabled(Long id, boolean enabled) {
        ChecklistTemplate t = requireCustom(id);
        t.enabled = enabled;
        t.updatedAt = TimeUtil.now();
        templateMapper.updateById(t);
        return row(t, true);
    }

    private ChecklistTemplate requireCustom(Long id) {
        ChecklistTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(1404, "模板不存在");
        }
        if (!"CUSTOM".equals(t.templateType)) {
            throw new BizException(422, "官方模板（TSG 附件）只读，如需补充请新建自定义模板");
        }
        return t;
    }

    private Map<String, Object> customPayload(ChecklistTemplate t, Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("itemCode", t.itemCode);
        p.put("seq", t.seq == null ? 0 : t.seq);
        p.put("freq", "CUSTOM");
        p.put("name", t.name);
        p.put("requirement", str(body, "requirement"));
        p.put("judgeType", t.judgeType);
        p.put("valueMin", body.get("valueMin"));
        p.put("valueMax", body.get("valueMax"));
        p.put("valueUnit", str(body, "valueUnit"));
        p.put("isKey", t.isKey);
        p.put("photoRequired", t.photoRequired);
        return p;
    }

    private Map<String, Object> row(ChecklistTemplate t, boolean withPayload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("templateType", nz(t.templateType));
        m.put("appendix", nz(t.appendix));
        m.put("categoryScope", nz(t.categoryScope));
        m.put("freq", nz(t.freq));
        m.put("itemCode", nz(t.itemCode));
        m.put("seq", t.seq == null ? 0 : t.seq);
        m.put("name", nz(t.name));
        m.put("judgeType", nz(t.judgeType));
        m.put("isKey", Boolean.TRUE.equals(t.isKey));
        m.put("photoRequired", Boolean.TRUE.equals(t.photoRequired));
        m.put("enabled", Boolean.TRUE.equals(t.enabled));
        if (withPayload) {
            Map<String, Object> payload = JsonUtil.readMap(t.payload);
            m.put("requirement", payload == null ? "" : String.valueOf(payload.getOrDefault("requirement", "")));
            m.put("payload", payload);
        }
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
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
