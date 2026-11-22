package com.cqwlw.maintenance.common;

import com.cqwlw.maintenance.util.JsonUtil;

import java.util.List;
import java.util.Map;

public final class Paginate {

    private Paginate() {
    }

    public static Map<String, Object> of(List<?> all, Map<String, String> query) {
        int page = Math.max(1, parseInt(query == null ? null : query.get("page"), 1));
        int size = Math.max(1, parseInt(query == null ? null : query.get("size"), 20));
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return JsonUtil.map("list", all.subList(from, to), "total", all.size());
    }

    private static int parseInt(String s, int dft) {
        try {
            return s == null || s.isBlank() ? dft : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
