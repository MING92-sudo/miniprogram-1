package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportPayloadValidatorTest {

    private static final List<String> REQUIRED_TEXT_FIELDS = List.of(
            "elevatorCode", "unitPrincipal", "unitPrincipalPhone",
            "elevatorAdminister", "elevatorAdministerPhone", "emergencyPhone",
            "insideNumber", "workMenegerName", "workMenegerPhone",
            "workMan1Id", "workMan2Id", "startTime", "endTime",
            "recorder", "recorderPhone", "originalRecordId", "deviceCode",
            "nextMaintenanceDate");

    private static Map<String, Object> completePayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("elevatorCode", "212520");
        p.put("unitPrincipal", "使用单位负责人");
        p.put("unitPrincipalPhone", "13800000001");
        p.put("elevatorAdminister", "安全管理员");
        p.put("elevatorAdministerPhone", "13800000002");
        p.put("emergencyPhone", "023-12345678");
        p.put("insideNumber", "KT-01");
        p.put("workMenegerName", "维保经理");
        p.put("workMenegerPhone", "13800000003");
        p.put("workMan1Id", "platform-worker-1");
        p.put("workMan2Id", "platform-worker-2");
        p.put("startTime", "2026-11-22 08:00:00");
        p.put("endTime", "2026-11-22 09:00:00");
        p.put("workType", "HM");
        p.put("recorder", "主维保");
        p.put("recorderPhone", "13800000004");
        p.put("originalRecordId", "1948179075784507801");
        p.put("problemCode", List.of("S0"));
        p.put("deviceCode", "31305001122014017242");
        p.put("nextMaintenanceDate", "2026-12-07");
        return p;
    }

    @Test
    void acceptsCompletePayloadAndAllowsBlankWorkType() {
        assertDoesNotThrow(() -> ReportPayloadValidator.requireComplete(completePayload()));
        Map<String, Object> p = completePayload();
        p.put("workType", "");
        assertDoesNotThrow(() -> ReportPayloadValidator.requireComplete(p));
    }

    @Test
    void rejectsEveryBlankRequiredField() {
        for (String field : REQUIRED_TEXT_FIELDS) {
            Map<String, Object> p = completePayload();
            p.put(field, "  ");
            BizException e = assertThrows(BizException.class,
                    () -> ReportPayloadValidator.requireComplete(p), field);
            assertTrue(e.getMessage().contains(field), e.getMessage());
        }
        Map<String, Object> noProblems = completePayload();
        noProblems.put("problemCode", List.of());
        BizException e = assertThrows(BizException.class,
                () -> ReportPayloadValidator.requireComplete(noProblems));
        assertTrue(e.getMessage().contains("problemCode"), e.getMessage());
    }

    @Test
    void rejectsInvalidDatesAndTimeSequence() {
        Map<String, Object> badStart = completePayload();
        badStart.put("startTime", "2026-02-30 08:00:00");
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(badStart));

        Map<String, Object> badEnd = completePayload();
        badEnd.put("endTime", "2026/11/22 09:00:00");
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(badEnd));

        Map<String, Object> reversed = completePayload();
        reversed.put("endTime", "2026-11-22 08:00:00");
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(reversed));

        Map<String, Object> badNext = completePayload();
        badNext.put("nextMaintenanceDate", "2026-02-30");
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(badNext));
    }

    @Test
    void rejectsInvalidPhonesWorkTypeAndProblemCodes() {
        for (String field : List.of("unitPrincipalPhone", "elevatorAdministerPhone",
                "workMenegerPhone", "recorderPhone")) {
            Map<String, Object> p = completePayload();
            p.put(field, "12345");
            BizException e = assertThrows(BizException.class,
                    () -> ReportPayloadValidator.requireComplete(p), field);
            assertTrue(e.getMessage().contains(field), e.getMessage());
        }

        Map<String, Object> badWorkType = completePayload();
        badWorkType.put("workType", "UNKNOWN");
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(badWorkType));

        Map<String, Object> badProblem = completePayload();
        badProblem.put("problemCode", List.of("S0", "BAD"));
        assertThrows(BizException.class, () -> ReportPayloadValidator.requireComplete(badProblem));
    }
}
