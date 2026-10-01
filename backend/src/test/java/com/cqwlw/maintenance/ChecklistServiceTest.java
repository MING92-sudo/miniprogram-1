package com.cqwlw.maintenance;

import com.cqwlw.maintenance.service.ChecklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查项模板（257 行）：累加式频次链、周期项默认"本次无需执行"、
 * 关键项照片留证、异常项隐患码。
 */
class ChecklistServiceTest {

    private ChecklistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ChecklistService();
        service.load();
    }

    @Test
    void buildChecklistAccumulatesFreqChain() {
        List<Map<String, Object>> hm = service.buildChecklist("HM", "曳引驱动电梯");
        List<Map<String, Object>> oy = service.buildChecklist("OY", "曳引驱动电梯");
        assertEquals(31, hm.size());
        assertEquals(76, oy.size());
        assertTrue(oy.size() > hm.size());
    }

    @Test
    void periodicItemsDefaultNotInThisRun() {
        List<Map<String, Object>> items = service.buildChecklist("HM", "曳引驱动电梯");
        long periodic = items.stream().filter(i -> Boolean.TRUE.equals(i.get("notInThisRun"))).count();
        long withFlag = items.stream().filter(i ->
                hasText(i.get("execCycleMonth")) || hasText(i.get("ageCondition"))
                        || hasText(i.get("seasonWindow"))).count();
        assertEquals(withFlag, periodic);
    }

    private static boolean hasText(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }

    @Test
    void makeDoneItemsFillsKeyPhotosAndAbnormalCode() {
        List<Map<String, Object>> items = service.makeDoneItems("HM", true, "曳引驱动电梯");
        for (Map<String, Object> it : items) {
            if (Boolean.TRUE.equals(it.get("notInThisRun"))) {
                continue;
            }
            if (Boolean.TRUE.equals(it.get("isKey")) && Boolean.TRUE.equals(it.get("photoRequired"))) {
                assertFalse(((List<?>) it.get("photoFileIds")).isEmpty(),
                        "关键项 " + it.get("itemCode") + " 须有照片");
            }
        }
        Map<String, Object> abnormal = items.stream()
                .filter(i -> "ABNORMAL".equals(String.valueOf(i.get("result")))).findFirst().orElse(null);
        assertTrue(abnormal != null);
        assertEquals("S5", abnormal.get("problemCode"));
    }

    @Test
    void templateItemsUseAppendixByCategory() {
        assertEquals(76, service.templateItems("曳引驱动电梯").size());
        assertEquals("A", service.appendix("曳引驱动电梯"));
    }
}
