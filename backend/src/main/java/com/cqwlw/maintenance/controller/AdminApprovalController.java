package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.ApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端·定位异常申述审核（GET /approvals + POST /approvals/{id}/audit）。
 * 读 LEADER+、审核 ADMIN+（拦截器承担）。
 */
@RestController
public class AdminApprovalController {

    private final ApprovalService approvalService;

    public AdminApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/admin/approvals")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        // bizType 一期仅 LOCATION_APPEAL；预留扩展
        return ApiResponse.ok(approvalService.listApprovals(Map.of(
                "status", nz(status), "page", nz(page), "size", nz(size))));
    }

    @PostMapping("/admin/approvals/{id}/audit")
    public ApiResponse<Map<String, Object>> audit(@PathVariable String id,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        boolean approved = Boolean.parseBoolean(String.valueOf(body.get("approved")));
        return ApiResponse.ok(approvalService.audit(id, approved,
                str(body, "comment"), String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID))));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
