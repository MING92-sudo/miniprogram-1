package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.BizExportApproval;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.service.DataDomainService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** M5 数据域端点：统计/审计只读/系统配置/敏感导出审批。 */
@RestController
public class DataDomainController {

    private final DataDomainService service;

    public DataDomainController(DataDomainService service) {
        this.service = service;
    }

    private static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.STATS_VIEW);
        return ApiResponse.ok(service.stats());
    }

    @GetMapping("/audit")
    public ApiResponse<List<SysAuditLog>> audit(@RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.AUDIT_VIEW);
        return ApiResponse.ok(service.auditLog(limit));
    }

    @GetMapping("/settings")
    public ApiResponse<Map<String, String>> settings(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.CONFIG_MANAGE);
        return ApiResponse.ok(service.config());
    }

    @PutMapping("/settings/{key}")
    public ApiResponse<SysConfig> setSetting(@PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.CONFIG_MANAGE);
        return ApiResponse.ok(service.setConfig(key, String.valueOf(body.get("value")), actor(req)));
    }

    @GetMapping("/export-approvals")
    public ApiResponse<List<BizExportApproval>> approvals(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.DATA_EXPORT_SENSITIVE);
        return ApiResponse.ok(service.approvals());
    }

    @PostMapping("/export-approvals")
    public ApiResponse<BizExportApproval> requestExport(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.DATA_EXPORT_SENSITIVE);
        return ApiResponse.ok(service.requestExport(str(body, "purpose"), str(body, "scope"), actor(req)));
    }

    @PostMapping("/export-approvals/{id}/approve")
    public ApiResponse<BizExportApproval> approve(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.DATA_EXPORT_SENSITIVE);
        return ApiResponse.ok(service.approveExport(id, actor(req)));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}