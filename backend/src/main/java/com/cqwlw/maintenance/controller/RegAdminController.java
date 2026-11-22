package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理端·平台同步与上报日志：GET /reg/upload-logs —— reg_upload_log 分页查询，digest 只读脱敏展示； */
@RestController
public class RegAdminController {

    private final AdminService adminService;

    public RegAdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/reg/upload-logs")
    public ApiResponse<Map<String, Object>> uploadLogs(
            @RequestParam(required = false) String originalRecordId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(adminService.uploadLogs(Map.of(
                "originalRecordId", nz(originalRecordId),
                "status", nz(status),
                "action", nz(action),
                "page", nz(page),
                "size", nz(size))));
    }

    @GetMapping("/reg/sync-status")
    @RequirePerm(PermissionMatrix.REPORT_VIEW)
    public ApiResponse<Map<String, Object>> syncStatus() {
        return ApiResponse.ok(adminService.syncStatus());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
