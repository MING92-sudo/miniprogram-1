package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.ChecklistTemplate;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * 检查项清单生成：按维保频次累加式生成清单。官方模板双路径——DB 已播种 OFFICIAL 时走 DB，
 * 否则回落 classpath checklist-template.json，两路径结果须逐字段一致。
 * 特殊类别（消防/防爆）追加启用的 CUSTOM 模板；未配置仅提示不阻断。
 */
@Service
public class ChecklistService {

    private static final Map<String, String> WORK_TYPE_LABEL = Map.of(
            "HM", "半月维保", "TM", "季度维保", "SM", "半年维保", "OY", "年度维保", "FM", "按需维保");

    private Map<String, Map<String, List<Map<String, Object>>>> appendixTpls;
    private Map<String, List<String>> freqChain;
    private Map<String, Map<String, String>> freqLabels;
    private Map<String, String> categoryAppendix;

    /** 模板库（可选：Spring 注入；单测构造时为 null → JSON 路径） */
    private ChecklistTemplateMapper templateMapper;

    @Autowired(required = false)
    public void setTemplateMapper(ChecklistTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() throws Exception {
        try (InputStream in = new ClassPathResource("checklist-template.json").getInputStream()) {
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            appendixTpls = (Map<String, Map<String, List<Map<String, Object>>>>) (Object) root.get("APPENDIX_TPLS");
            freqChain = (Map<String, List<String>>) (Object) root.get("FREQ_CHAIN");
            freqLabels = (Map<String, Map<String, String>>) (Object) root.get("FREQ_LABELS");
            categoryAppendix = (Map<String, String>) (Object) root.get("CATEGORY_APPENDIX");
        }
    }

    public String label(String workTypeCode) {
        return WORK_TYPE_LABEL.getOrDefault(workTypeCode, workTypeCode);
    }

    public List<String> chain(String workTypeCode) {
        return freqChain.getOrDefault(workTypeCode, freqChain.get("HM"));
    }

    public String appendix(String categoryCode) {
        return categoryAppendix.getOrDefault(categoryCode, "A");
    }

    /** 自行检查模板：年度维保项并集（原始模板条目，非工单检查项） */
    public List<Map<String, Object>> templateItems(String categoryCode) {
        String appendix = appendix(categoryCode);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String freq : freqChain.get("OY")) {
            items.addAll(appendixTpls.get(appendix).get(freq));
        }
        return items;
    }

    /** 累加式生成工单检查清单（周期条目默认"本次无需执行"）；不涉及特殊类别 */
    public List<Map<String, Object>> buildChecklist(String workTypeCode, String categoryCode) {
        return buildChecklist(workTypeCode, categoryCode, null);
    }

    /** 含特殊类别：special_type（消防/防爆）匹配的启用自定义模板追加在官方项之后 */
    public List<Map<String, Object>> buildChecklist(String workTypeCode, String categoryCode, String specialType) {
        String appendix = appendix(categoryCode);
        Map<String, List<Map<String, Object>>> tpls = officialTpls(appendix);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String freq : chain(workTypeCode)) {
            for (Map<String, Object> tpl : tpls.get(freq)) {
                // 空串视为未配置（与 mock 一致，JS falsy）
                boolean periodic = hasText(tpl.get("execCycleMonth"))
                        || hasText(tpl.get("ageCondition"))
                        || hasText(tpl.get("seasonWindow"));
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", "ci_" + tpl.get("itemCode"));
                it.put("itemCode", tpl.get("itemCode"));
                it.put("seq", tpl.get("seq"));
                it.put("freq", tpl.get("freq"));
                it.put("freqLabel", freqLabels.get(appendix).get(freq));
                it.put("name", tpl.get("name"));
                it.put("requirement", tpl.get("requirement"));
                it.put("judgeType", tpl.get("judgeType"));
                it.put("valueMin", tpl.get("valueMin"));
                it.put("valueMax", tpl.get("valueMax"));
                it.put("valueUnit", tpl.get("valueUnit"));
                it.put("isKey", Boolean.TRUE.equals(tpl.get("isKey")));
                it.put("photoRequired", Boolean.TRUE.equals(tpl.get("photoRequired")));
                it.put("ageCondition", tpl.get("ageCondition"));
                it.put("execCycleMonth", tpl.get("execCycleMonth"));
                it.put("seasonWindow", tpl.get("seasonWindow"));
                it.put("notInThisRun", periodic);
                it.put("nextRunText", periodic ? nextRunText(tpl) : "");
                it.put("result", null);
                it.put("value", null);
                it.put("valueText", "");
                it.put("abnormalDesc", "");
                it.put("problemCode", "");
                it.put("skipReason", "");
                it.put("photos", new ArrayList<>());
                it.put("photoFileIds", new ArrayList<>());
                it.put("recordedAt", "");
                items.add(it);
            }
        }
        items.addAll(customItems(specialType));
        return items;
    }

    /** 官方模板：DB 已播种则走 DB（payload 原文反序列化），否则回落 JSON；两路径结果一致 */
    private Map<String, List<Map<String, Object>>> officialTpls(String appendix) {
        if (templateMapper != null) {
            List<ChecklistTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<ChecklistTemplate>()
                    .eq(ChecklistTemplate::getTemplateType, "OFFICIAL")
                    .eq(ChecklistTemplate::getAppendix, appendix)
                    .eq(ChecklistTemplate::getEnabled, true)
                    .orderByAsc(ChecklistTemplate::getSeq));
            if (rows != null && !rows.isEmpty()) {
                Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
                for (String freq : freqChain.getOrDefault("OY", List.of("HALF", "QUARTER", "HALF_YEAR", "YEAR"))) {
                    grouped.put(freq, new ArrayList<>());
                }
                for (ChecklistTemplate row : rows) {
                    // 防御：非官方行（mock/脏数据）不进官方周期链
                    if (!"OFFICIAL".equals(row.templateType) || row.freq == null) {
                        continue;
                    }
                    try {
                        Map<String, Object> tpl = MAPPER.readValue(row.payload,
                                new TypeReference<Map<String, Object>>() {
                                });
                        grouped.computeIfAbsent(row.freq, k -> new ArrayList<>()).add(tpl);
                    } catch (Exception ignored) {
                        // 单行 payload 损坏跳过，不阻断生成
                    }
                }
                return grouped;
            }
        }
        return appendixTpls.get(appendix);
    }

    /** 自定义模板追加（消防/防爆等，按 elevator.special_type 匹配启用中的 CUSTOM 行） */
    private List<Map<String, Object>> customItems(String specialType) {
        if (templateMapper == null || specialType == null || specialType.isBlank()) {
            return List.of();
        }
        List<ChecklistTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<ChecklistTemplate>()
                .eq(ChecklistTemplate::getTemplateType, "CUSTOM")
                .eq(ChecklistTemplate::getCategoryScope, specialType)
                .eq(ChecklistTemplate::getEnabled, true)
                .orderByAsc(ChecklistTemplate::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChecklistTemplate row : rows) {
            // 防御：仅接受 CUSTOM 行（过滤条件在查询里，这里兜底）
            if (!"CUSTOM".equals(row.templateType) || !Boolean.TRUE.equals(row.enabled)) {
                continue;
            }
            try {
                Map<String, Object> tpl = MAPPER.readValue(row.payload,
                        new TypeReference<Map<String, Object>>() {
                        });
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", "ci_" + row.itemCode);
                it.put("itemCode", row.itemCode);
                it.put("seq", row.seq);
                it.put("freq", "CUSTOM");
                it.put("freqLabel", "自定义项目（制造单位要求）");
                it.put("name", row.name);
                it.put("requirement", tpl.getOrDefault("requirement", ""));
                it.put("judgeType", row.judgeType);
                it.put("valueMin", tpl.get("valueMin"));
                it.put("valueMax", tpl.get("valueMax"));
                it.put("valueUnit", tpl.get("valueUnit"));
                it.put("isKey", Boolean.TRUE.equals(row.isKey));
                it.put("photoRequired", Boolean.TRUE.equals(row.photoRequired));
                it.put("ageCondition", "");
                it.put("execCycleMonth", "");
                it.put("seasonWindow", "");
                it.put("notInThisRun", false);
                it.put("nextRunText", "");
                it.put("result", null);
                it.put("value", null);
                it.put("valueText", "");
                it.put("abnormalDesc", "");
                it.put("problemCode", "");
                it.put("skipReason", "");
                it.put("photos", new ArrayList<>());
                it.put("photoFileIds", new ArrayList<>());
                it.put("recordedAt", "");
                items.add(it);
            } catch (Exception ignored) {
                // 单行 payload 损坏跳过，不阻断生成
            }
        }
        return items;
    }

    private String nextRunText(Map<String, Object> tpl) {
        Object months = tpl.get("execCycleMonth");
        if (months == null) {
            return "按周期执行";
        }
        int m = ((Number) months).intValue();
        return "下次执行：" + TimeUtil.now().plusMonths(m).toString().substring(0, 7);
    }

    private static boolean hasText(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }

    /** 预填"已完成"演示检查项（关键项附照片留证，演示种子数据用） */
    public List<Map<String, Object>> makeDoneItems(String workTypeCode, boolean withAbnormal, String categoryCode) {
        List<Map<String, Object>> items = buildChecklist(workTypeCode, categoryCode);
        for (Map<String, Object> it : items) {
            if (Boolean.TRUE.equals(it.get("notInThisRun"))) {
                continue;
            }
            String judgeType = String.valueOf(it.get("judgeType"));
            it.put("result", "NORMAL");
            if ("NUMERIC".equals(judgeType)) {
                Object min = it.get("valueMin");
                it.put("value", min != null ? ((Number) min).intValue() + 1 : 8);
            } else if ("MANUFACTURER".equals(judgeType)) {
                it.put("valueText", "符合本机说明书要求");
            }
            if (Boolean.TRUE.equals(it.get("isKey")) && Boolean.TRUE.equals(it.get("photoRequired"))) {
                it.put("photos", List.of("https://picsum.photos/seed/em-elev/600/450?k=" + it.get("itemCode")));
                it.put("photoFileIds", List.of("mock_file_" + it.get("itemCode")));
            }
            it.put("recordedAt", TimeUtil.format(TimeUtil.now()));
        }
        if (withAbnormal) {
            Map<String, Object> item = items.stream()
                    .filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun")))
                    .filter(i -> String.valueOf(i.get("itemCode")).equals("A-1-18")
                            || String.valueOf(i.get("name")).contains("报警"))
                    .findFirst()
                    .orElse(items.stream().filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun"))).findFirst().orElse(null));
            if (item != null) {
                item.put("result", "ABNORMAL");
                item.put("abnormalDesc", "轿内报警装置通话杂音大，已清洁触点并复测");
                item.put("problemCode", "S5");
                item.put("photos", List.of("https://picsum.photos/seed/em-elev/600/450?abn=" + item.get("itemCode")));
                item.put("photoFileIds", List.of("mock_file_abn_" + item.get("itemCode")));
            }
        }
        return items;
    }
}
