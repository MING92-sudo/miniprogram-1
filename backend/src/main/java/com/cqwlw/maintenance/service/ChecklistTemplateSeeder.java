package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.ChecklistTemplate;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * 检查项模板落库：把 classpath checklist-template.json 的 257 行官方模板扁平化播种进
 * `checklist_template`（template_type=OFFICIAL），payload 存条目 JSON 原文，
 * ChecklistService 据此生成清单，与 JSON 路径须逐字段一致。
 * 表为空时播种，重复启动幂等；CUSTOM 模板由管理端维护。
 */
@Service
public class ChecklistTemplateSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChecklistTemplateSeeder.class);

    /** OFFICIAL 行的 category_scope（与 JSON CATEGORY_APPENDIX 对应） */
    public static final Map<String, String> APPENDIX_SCOPE =
            Map.of("A", "曳引与强制驱动电梯", "B", "液压驱动电梯", "C", "杂物电梯", "D", "自动扶梯与自动人行道");

    private final ChecklistTemplateMapper templateMapper;

    public ChecklistTemplateSeeder(ChecklistTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Long existing = templateMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChecklistTemplate>()
                .eq(ChecklistTemplate::getTemplateType, "OFFICIAL"));
        if (existing != null && existing > 0) {
            log.info("检查项模板已存在 {} 行，跳过播种", existing);
            return;
        }
        List<ChecklistTemplate> rows = parseOfficialRows();
        for (ChecklistTemplate row : rows) {
            templateMapper.insert(row);
        }
        log.info("检查项模板播种完成：{} 行（附件 A—D）", rows.size());
    }

    /** 纯函数：JSON → OFFICIAL 行（供播种与单测共用） */
    public List<ChecklistTemplate> parseOfficialRows() throws Exception {
        try (InputStream in = new ClassPathResource("checklist-template.json").getInputStream()) {
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            return parseOfficialRows(root);
        }
    }

    @SuppressWarnings("unchecked")
    public List<ChecklistTemplate> parseOfficialRows(Map<String, Object> root)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Map<String, List<Map<String, Object>>>> tpls =
                (Map<String, Map<String, List<Map<String, Object>>>>) (Object) root.get("APPENDIX_TPLS");
        List<ChecklistTemplate> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Map<String, Object>>>> appendix : tpls.entrySet()) {
            String appendixCode = appendix.getKey();
            for (Map.Entry<String, List<Map<String, Object>>> freq : appendix.getValue().entrySet()) {
                int seq = 0;
                for (Map<String, Object> item : freq.getValue()) {
                    ChecklistTemplate row = new ChecklistTemplate();
                    row.templateType = "OFFICIAL";
                    row.appendix = appendixCode;
                    row.categoryScope = APPENDIX_SCOPE.getOrDefault(appendixCode, appendixCode);
                    row.freq = freq.getKey();
                    row.itemCode = str(item.get("itemCode"));
                    row.seq = ++seq;
                    row.name = str(item.get("name"));
                    row.judgeType = str(item.get("judgeType"));
                    row.isKey = Boolean.TRUE.equals(item.get("isKey"));
                    row.photoRequired = Boolean.TRUE.equals(item.get("photoRequired"));
                    row.enabled = true;
                    row.payload = MAPPER.writeValueAsString(item);
                    row.createdAt = LocalDateTime.now();
                    row.updatedAt = LocalDateTime.now();
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
