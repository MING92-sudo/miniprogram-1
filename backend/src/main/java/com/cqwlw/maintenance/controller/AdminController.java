package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端·看板/记录/统计/电梯富视图（docs/09 §4.1 第一批路由）。
 * 角色门禁由 AdminRoleInterceptor 承担（读：LEADER/ADMIN/SYS_ADMIN）。
 */
@RestController
public class AdminController {

    private final AdminService adminService;
    private final AdminArchiveService archiveService;

    public AdminController(AdminService adminService, AdminArchiveService archiveService) {
        this.adminService = adminService;
        this.archiveService = archiveService;
    }

    @GetMapping("/admin/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(adminService.dashboard());
    }

    /** 电梯管理端富视图（含 geoStatus 位置待补高亮，docs/09 决策 #4）；小程序列表仍走 GET /elevators */
    @GetMapping("/admin/elevators")
    public ApiResponse<List<Map<String, Object>>> elevators() {
        return ApiResponse.ok(archiveService.elevatorAdminList());
    }

    @GetMapping("/admin/records")
    public ApiResponse<Map<String, Object>> records(
            @RequestParam(required = false) String reportStatus,
            @RequestParam(required = false) String confirmStatus,
            @RequestParam(required = false) String elevatorCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(adminService.records(Map.of(
                "reportStatus", nz(reportStatus),
                "confirmStatus", nz(confirmStatus),
                "elevatorCode", nz(elevatorCode),
                "keyword", nz(keyword),
                "dateFrom", nz(dateFrom),
                "dateTo", nz(dateTo),
                "page", nz(page),
                "size", nz(size))));
    }

    @GetMapping("/admin/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(required = false) String days) {
        return ApiResponse.ok(adminService.stats(days));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
