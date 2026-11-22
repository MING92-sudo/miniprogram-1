package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 平台 2.6 报文最终门禁：签退冻结与人工重报共用，禁止空必填值或非法值出系统。 */
public final class ReportPayloadValidator {

    private static final List<String> REQUIRED_TEXT_FIELDS = List.of(
            "elevatorCode", "unitPrincipal", "unitPrincipalPhone",
            "elevatorAdminister", "elevatorAdministerPhone", "emergencyPhone",
            "insideNumber", "workMenegerName", "workMenegerPhone",
            "workMan1Id", "workMan2Id", "startTime", "endTime",
            "recorder", "recorderPhone", "originalRecordId", "deviceCode",
            "nextMaintenanceDate");
    private static final List<String> MOBILE_FIELDS = List.of(
            "unitPrincipalPhone", "elevatorAdministerPhone", "workMenegerPhone", "recorderPhone");
    private static final Set<String> WORK_TYPES = Set.of("FM", "HM", "TM", "SM", "OY");
    private static final Set<String> PROBLEM_CODES = Set.of("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7");
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private ReportPayloadValidator() {
    }

    public static void requireComplete(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new BizException(422, "2.6 报文为空，无法上报");
        }
        for (String field : REQUIRED_TEXT_FIELDS) {
            if (text(payload.get(field)).isEmpty()) {
                throw new BizException(422, "2.6 必填字段为空: " + field);
            }
        }
        Object rawProblems = payload.get("problemCode");
        if (!(rawProblems instanceof List<?> problems) || problems.isEmpty()) {
            throw new BizException(422, "2.6 必填字段为空: problemCode");
        }
        for (Object code : problems) {
            if (!PROBLEM_CODES.contains(text(code))) {
                throw new BizException(422, "2.6 严重事故隐患代码非法: " + text(code));
            }
        }
        for (String field : MOBILE_FIELDS) {
            if (!MOBILE.matcher(text(payload.get(field))).matches()) {
                throw new BizException(422, "2.6 手机号格式非法: " + field);
            }
        }
        String workType = text(payload.get("workType"));
        if (!workType.isEmpty() && !WORK_TYPES.contains(workType)) {
            throw new BizException(422, "2.6 维保类别代码非法: " + workType);
        }
        LocalDateTime start = parseDateTime("startTime", text(payload.get("startTime")));
        LocalDateTime end = parseDateTime("endTime", text(payload.get("endTime")));
        if (!end.isAfter(start)) {
            throw new BizException(422, "2.6 维保结束时间必须晚于开始时间");
        }
        try {
            LocalDate.parse(text(payload.get("nextMaintenanceDate")), DATE);
        } catch (DateTimeParseException e) {
            throw new BizException(422, "2.6 下次维保日期格式非法: nextMaintenanceDate");
        }
    }

    private static LocalDateTime parseDateTime(String field, String value) {
        try {
            return LocalDateTime.parse(value, DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new BizException(422, "2.6 维保时间格式非法: " + field);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
