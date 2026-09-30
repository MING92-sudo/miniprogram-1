package com.cqwlw.maintenance.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * 检查项清单生成：按维保频次累加式生成 TSG 附件 A—D 清单（docs/01 §3.7.3、docs/08 附件B/C/D 模板）。
 * 模板数据源自 mock/checklist-template.js（257 行，构建期导出为 checklist-template.json）。
 */
@Service
public class ChecklistService {

    private static final Map<String, String> WORK_TYPE_LABEL = Map.of(
            "HM", "半月维保", "TM", "季度维保", "SM", "半年维保", "OY", "年度维保", "FM", "按需维保");

    private Map<String, Map<String, List<Map<String, Object>>>> appendixTpls;
    private Map<String, List<String>> freqChain;
    private Map<String, Map<String, String>> freqLabels;
    private Map<String, String> categoryAppendix;

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

    /** 累加式生成工单检查清单（周期条目默认"本次无需执行"，docs/03 §6.1） */
    public List<Map<String, Object>> buildChecklist(String workTypeCode, String categoryCode) {
        String appendix = appendix(categoryCode);
        Map<String, List<Map<String, Object>>> tpls = appendixTpls.get(appendix);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String freq : chain(workTypeCode)) {
            for (Map<String, Object> tpl : tpls.get(freq)) {
                // 与 mock 口径一致：空串视为未配置（JS falsy）
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
