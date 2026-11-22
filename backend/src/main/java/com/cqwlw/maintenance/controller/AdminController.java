package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.AdminService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AdminController {

    private final AdminService adminService;
    private final AdminArchiveService archiveService;
    private final EmployeeScopeService scopeService;

    public AdminController(AdminService adminService, AdminArchiveService archiveService,
                           EmployeeScopeService scopeService) {
        this.adminService = adminService;
        this.archiveService = archiveService;
        this.scopeService = scopeService;
    }

    @GetMapping("/admin/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(adminService.dashboard());
    }

    @GetMapping("/admin/elevators")
    public ApiResponse<List<Map<String, Object>>> elevators() {
        return ApiResponse.ok(archiveService.elevatorAdminList());
    }

    @RequirePerm(PermissionMatrix.RECORD_VIEW)
    @GetMapping("/admin/records")
    public ApiResponse<Map<String, Object>> records(
            @RequestParam(required = false) String reportStatus,
            @RequestParam(required = false) String confirmStatus,
            @RequestParam(required = false) String elevatorCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            HttpServletRequest request) {
        Object empId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        Employee employee = scopeService.require(empId == null ? null : String.valueOf(empId));
        return ApiResponse.ok(adminService.records(Map.of(
                "reportStatus", nz(reportStatus),
                "confirmStatus", nz(confirmStatus),
                "elevatorCode", nz(elevatorCode),
                "keyword", nz(keyword),
                "dateFrom", nz(dateFrom),
                "dateTo", nz(dateTo),
                "page", nz(page),
                "size", nz(size)), scopeService.visibleReadPlatformIds(employee)));
    }

    @GetMapping("/admin/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(required = false) String days) {
        return ApiResponse.ok(adminService.stats(days));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
