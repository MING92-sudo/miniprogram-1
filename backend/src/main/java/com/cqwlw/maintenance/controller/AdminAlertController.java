package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端·预警（GET /alerts、GET/PUT /alert-rules）。
 * 规则读 LEADER+、改 ADMIN+；生成预警 ADMIN+（拦截器承担角色门禁）。
 */
@RestController
public class AdminAlertController {

    private final AlertService alertService;

    public AdminAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/admin/alert-rules")
    public ApiResponse<List<Map<String, Object>>> rules() {
        return ApiResponse.ok(alertService.listRules());
    }

    @PutMapping("/admin/alert-rules/{id}")
    public ApiResponse<Map<String, Object>> updateRule(@PathVariable String id,
                                                       @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(alertService.updateRule(id, body));
    }

    @GetMapping("/admin/alerts")
    public ApiResponse<Map<String, Object>> alerts(
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(alertService.listAlerts(Map.of(
                "ruleType", nz(ruleType), "status", nz(status),
                "page", nz(page), "size", nz(size))));
    }

    @PostMapping("/admin/alerts/generate")
    public ApiResponse<Map<String, Object>> generate() {
        return ApiResponse.ok(alertService.generate());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
