package com.cqwlw.maintenance.util;

import com.cqwlw.maintenance.entity.Elevator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTimeTest {

    @Test
    void localDateTimeSerializedAsFixedPatternNotArray() {
        Elevator el = new Elevator();
        el.id = "el_test";
        el.handoverAt = LocalDateTime.of(2026, 11, 20, 9, 5, 3);

        String json = JsonUtil.write(el);
        assertTrue(json.contains("\"2026-11-20 09:05:03\""), "LocalDateTime 应为 yyyy-MM-dd HH:mm:ss，实际：" + json);
    }

    @Test
    void convertEntityToMapWithLocalDateTimeDoesNotThrow() {
        Elevator el = new Elevator();
        el.id = "el_test";
        el.handoverAt = LocalDateTime.of(2026, 11, 20, 0, 0, 0);

        Map<String, Object> map = JsonUtil.MAPPER.convertValue(el, Map.class);
        assertEquals("el_test", map.get("id"));
        assertEquals("2026-11-20 00:00:00", map.get("handoverAt"));
    }
}
