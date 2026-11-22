package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.MigrationIssueService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 仅在 controller 层做门控与委托，业务校验在 MigrationIssueService。 */
@RestController
public class AdminMigrationIssueController {

    private final MigrationIssueService service;

    public AdminMigrationIssueController(MigrationIssueService service) {
        this.service = service;
    }

    private static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    /** query 参数 type 映射到 service 的 issueType（列表筛选语义） */
    @GetMapping("/admin/migration-issues")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resolution,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.AUDIT_VIEW);
        return ApiResponse.ok(service.list(type, resolution, page, size));
    }

    /** 单条裁定；backfillUnitId/decision 可为 null，不用 String.valueOf(null) 造字面字符串 "null" */
    @SuppressWarnings("unchecked")
    @PostMapping("/admin/migration-issues/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.CONFIG_MANAGE);
        // decision 是对象（按对象类型下发的裁定项）；用 instanceof 判型，错误类型按缺省处理而不是 500
        Object rawDecision = body == null ? null : body.get("decision");
        Map<String, Object> decision = rawDecision instanceof Map ? (Map<String, Object>) rawDecision : null;
        return ApiResponse.ok(service.resolve(id,
                body == null ? null : (String) body.get("resolution"),
                body == null ? null : (String) body.get("backfillUnitId"),
                decision,
                body == null ? null : (String) body.get("note"),
                actor(req)));
    }
}
