package com.cqwlw.maintenance.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间统一口径：存取/展示一律 yyyy-MM-dd HH:mm:ss（GMT+8），时长 HH:mm:ss。
 * 与前端 utils/util.js 的 formatTime/parseTime 行为对齐。
 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeUtil() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static String format(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }

    public static String formatDate(LocalDate d) {
        return d == null ? "" : d.format(DATE_FMT);
    }

    public static LocalDateTime parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String date(LocalDateTime t) {
        return t == null ? "" : t.format(DATE_FMT);
    }

    /** 时长 HH:mm:ss（对齐 mock formatDuration） */
    public static String formatDuration(long millis) {
        long sec = Math.max(0, millis / 1000);
        return String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    public static LocalDateTime addDays(LocalDateTime t, int days) {
        return t.plusDays(days);
    }

    public static long toMillis(LocalDateTime t) {
        return t == null ? 0L : t.atZone(ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime fromMillis(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDateTime();
    }

    public static long minutesBetween(String start, String end) {
        LocalDateTime a = parse(start);
        LocalDateTime b = parse(end);
        if (a == null || b == null) {
            return 0L;
        }
        return Duration.between(a, b).toMinutes();
    }
}
