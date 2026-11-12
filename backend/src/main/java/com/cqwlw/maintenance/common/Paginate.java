package com.cqwlw.maintenance.common;

import com.cqwlw.maintenance.util.JsonUtil;

import java.util.List;
import java.util.Map;

/**
 * 内存分页（管理端列表通用）：{@code {list, total}}，page 从 1 开始，size 默认 20。
 * 数据量级为单维保单位的移交/建档单，先内存分页；将来上量再下沉到 SQL 分页。
 */
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
