package com.cqwlw.maintenance.util;

/** 日志脱敏：外部 HTTP 异常可能回显含凭证的完整 URL，查询串一律不落地。 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    public static String withoutQuery(String message) {
        if (message == null || message.isEmpty()) {
            return "-";
        }
        int query = message.indexOf('?');
        return query < 0 ? message : message.substring(0, query) + "?<query-redacted>";
    }
}
