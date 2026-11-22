package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.MaintenanceCalendarService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 维保日历：只读投影，不落库、不触发派单。 */
@RestController
public class MaintenanceCalendarController {

    private final MaintenanceCalendarService calendarService;

    public MaintenanceCalendarController(MaintenanceCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /** 未来排期投影：from/to 为 yyyy-MM-dd，缺省「今天 → +12 个月」 */
    @GetMapping("/maintenance-calendar")
    public ApiResponse<Map<String, Object>> calendar(@RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to,
                                                     HttpServletRequest request) {
        String empId = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID));
        return ApiResponse.ok(calendarService.calendar(empId, date(from), date(to)));
    }

    private static LocalDate date(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }
}
