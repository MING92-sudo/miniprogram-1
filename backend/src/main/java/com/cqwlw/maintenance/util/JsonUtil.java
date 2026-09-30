package com.cqwlw.maintenance.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 帮助器：复杂嵌套结构（检查项/记录明细/报文快照）在 JSON 列与 Map 间转换。
 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    public static List<Map<String, Object>> readList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
