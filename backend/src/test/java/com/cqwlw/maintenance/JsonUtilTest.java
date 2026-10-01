package com.cqwlw.maintenance;

import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JsonUtil 回归：隐患码 / 照片 URL 这类 JSON 列存的是**字符串数组**（WorkOrderService 签退冻结快照），
 * 必须用 readStringList 解析。历史上用 readList（List&lt;Map&gt;）会在签退与种子数据阶段抛
 * IllegalStateException（"Cannot construct instance of LinkedHashMap ... from String value ('S5')"），
 * 导致后端启动失败（SEED_DEMO_DATA=true）与工单/使用单位接口 500。
 */
class JsonUtilTest {

    @Test
    void readStringList_parsesHazardCodesAndPhotoUrls() {
        assertEquals(List.of("S0"), JsonUtil.readStringList("[\"S0\"]"));
        assertEquals(List.of("S1", "S5"), JsonUtil.readStringList("[\"S1\",\"S5\"]"));
        assertEquals(List.of("https://cdn.example.com/a.jpg"),
                JsonUtil.readStringList("[\"https://cdn.example.com/a.jpg\"]"));
        assertEquals(List.of(), JsonUtil.readStringList("[]"));
    }

    @Test
    void readStringList_treatsBlankAsEmpty() {
        assertEquals(List.of(), JsonUtil.readStringList(null));
        assertEquals(List.of(), JsonUtil.readStringList(""));
    }

    @Test
    void readList_keepsParsingObjectArrays_butRejectsStringArrays() {
        assertEquals(1, JsonUtil.readList("[{\"id\":\"ci_A-1-01\"}]").size());
        assertThrows(IllegalStateException.class, () -> JsonUtil.readList("[\"S5\"]"));
    }

    @Test
    void readMap_parsesFrozenReportPayload() {
        Map<String, Object> m = JsonUtil.readMap("{\"problemCode\":[\"S5\"],\"elevatorCode\":\"EM-2024-001\"}");
        assertEquals(List.of("S5"), m.get("problemCode"));
        assertEquals("EM-2024-001", m.get("elevatorCode"));
    }
}
