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

    /**
     * 字符串列表读取（隐患码 / 照片 URL 等：这些 JSON 列存的是字符串数组，不是对象数组）。
     * 平台 2.6 的 problemCode 为 JSON 数组（docs/01 §；docs/04 B.6 V1.1），
     * 用 readList（List&lt;Map&gt;）解析会因元素是字符串而失败。
     * 空值统一返回空列表，调用方无需再判空。
     */
    public static List<String> readStringList(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
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
