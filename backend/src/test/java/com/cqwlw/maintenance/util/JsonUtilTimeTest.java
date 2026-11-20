package com.cqwlw.maintenance.util;

import com.cqwlw.maintenance.entity.Elevator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonUtil 时间序列化回归（E2E 发现）：历史实现是裸 new ObjectMapper()，
 * 未注册 JavaTimeModule，convertValue(含 LocalDateTime 的实体, Map) 会抛
 * 「Java 8 date/time type not supported」，导致移交单/外接备案详情等接口 500。
 */
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
