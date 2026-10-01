package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.ChecklistTemplate;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.ChecklistTemplateSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检查清单双路径一致性：checklist_template 播种后 DB 路径生成结果
 * 必须与 JSON 路径逐字段一致；特殊类别（消防）追加启用中的 CUSTOM 自定义项。
 */
class ChecklistServiceDbPathTest {

    private ChecklistTemplateMapper mapper;
    private ChecklistService dbService;
    private ChecklistService jsonService;

    @BeforeEach
    void setUp() throws Exception {
        jsonService = new ChecklistService();
        jsonService.load();
        dbService = new ChecklistService();
        dbService.load();
        mapper = mock(ChecklistTemplateMapper.class);
        dbService.setTemplateMapper(mapper);
    }

    private List<ChecklistTemplate> officialRows(String appendix) throws Exception {
        ChecklistTemplateSeeder seeder = new ChecklistTemplateSeeder(mapper);
        List<ChecklistTemplate> all = seeder.parseOfficialRows();
        List<ChecklistTemplate> rows = new ArrayList<>();
        for (ChecklistTemplate r : all) {
            if (appendix.equals(r.appendix)) {
                rows.add(r);
            }
        }
        return rows;
    }

    @Test
    void dbPathProducesIdenticalChecklistToJsonPath() throws Exception {
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(officialRows("A"));
        List<Map<String, Object>> fromJson = jsonService.buildChecklist("HM", "曳引驱动电梯");
        List<Map<String, Object>> fromDb = dbService.buildChecklist("HM", "曳引驱动电梯");
        assertEquals(fromJson, fromDb);
        assertEquals(fromJson.size(), fromDb.size());
    }

    @Test
    void customTemplateAppendedForSpecialElevator() throws Exception {
        // official + custom 混合返回：custom freq=CUSTOM 不进官方周期链，只经 customItems 追加
        List<ChecklistTemplate> rows = new ArrayList<>(officialRows("A"));
        ChecklistTemplate custom = new ChecklistTemplate();
        custom.id = 999L;
        custom.templateType = "CUSTOM";
        custom.categoryScope = "消防电梯";
        custom.freq = "CUSTOM";
        custom.itemCode = "CT-TEST-1";
        custom.seq = 1;
        custom.name = "消防电梯专用：应急疏散广播测试";
        custom.judgeType = "QUALITATIVE";
        custom.isKey = true;
        custom.photoRequired = true;
        custom.enabled = true;
        custom.payload = com.cqwlw.maintenance.util.JsonUtil.write(Map.of(
                "itemCode", "CT-TEST-1", "freq", "CUSTOM", "name", custom.name,
                "requirement", "按制造单位要求逐层测试", "judgeType", "QUALITATIVE",
                "isKey", true, "photoRequired", true));
        rows.add(custom);
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(rows);

        List<Map<String, Object>> items = dbService.buildChecklist("HM", "曳引驱动电梯", "消防电梯");
        int officialCount = jsonService.buildChecklist("HM", "曳引驱动电梯").size();
        assertEquals(officialCount + 1, items.size());
        Map<String, Object> last = items.get(items.size() - 1);
        assertEquals("CT-TEST-1", last.get("itemCode"));
        assertEquals(Boolean.FALSE, last.get("notInThisRun"));
        assertEquals("自定义项目（制造单位要求）", last.get("freqLabel"));
        // 官方项不受影响
        assertEquals("ci_A-1-01", items.get(0).get("id"));
    }

    @Test
    void customNotAppendedWithoutSpecialType() throws Exception {
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(officialRows("A"));
        List<Map<String, Object>> items = dbService.buildChecklist("HM", "曳引驱动电梯", null);
        assertEquals(jsonService.buildChecklist("HM", "曳引驱动电梯").size(), items.size());
    }
}
