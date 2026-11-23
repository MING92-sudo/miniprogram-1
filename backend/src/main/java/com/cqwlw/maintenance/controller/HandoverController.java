package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.service.HandoverService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    /** 起草选项：与小程序/管理端共用使用单位档案，避免两套自由文本。 */
    @GetMapping("/handovers/options")
    public ApiResponse<Map<String, Object>> options(HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_CREATE);
        return ApiResponse.ok(handoverService.options());
    }

    /** 安装部发起移交（资料齐全后进入 PENDING_INST_REVIEW） */
    @PostMapping("/handovers")
    public ApiResponse<HandoverOrder> init(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_CREATE);
        return ApiResponse.ok(handoverService.init(body));
    }

    @GetMapping("/handovers")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String size,
                                                 HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        Map<String, String> query = new java.util.HashMap<>();
        query.put("status", status == null ? "" : status);
        query.put("page", page == null ? "" : page);
        query.put("size", size == null ? "" : size);
        @SuppressWarnings("unchecked")
        java.util.Set<String> roles = (java.util.Set<String>) req.getAttribute(
                com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_ROLES);
        return ApiResponse.ok(handoverService.list(query, roles));
    }

    /** 移交单详情 */
    @GetMapping("/handovers/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        return ApiResponse.ok(handoverService.detail(id));
    }

    /** 提交 3 必传资料（缺一 422） */
    @PostMapping("/handovers/{id}/submit-docs")
    public ApiResponse<HandoverOrder> submitDocs(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_DOC_UPLOAD);
        return ApiResponse.ok(handoverService.submitDocs(id, body));
    }

    /** 维保部经理分配接收工程师 */
    @PostMapping("/handovers/{id}/assign-receiver")
    public ApiResponse<HandoverOrder> assignReceiver(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_RECEIVE);
        return ApiResponse.ok(handoverService.assignReceiver(id, body));
    }

    /** 兼容入口：与 checklist/submit 共用 74 项查验校验。 */
    @PostMapping("/handovers/{id}/inspect")
    public ApiResponse<HandoverOrder> submitInspect(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_INSPECT);
        return ApiResponse.ok(handoverService.submitInspect(id, body));
    }

    /** 安装部上传整改前后照片 */
    @PostMapping("/handovers/{id}/rectify")
    public ApiResponse<HandoverOrder> submitRectify(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_RECTIFY);
        return ApiResponse.ok(handoverService.submitRectify(id, body));
    }

    /** 复验（通过回待确认） */
    @PostMapping("/handovers/{id}/recheck-rectify")
    public ApiResponse<HandoverOrder> recheckRectify(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_INSPECT);
        return ApiResponse.ok(handoverService.recheckRectify(id, body));
    }

    /** 确认后归档。 */
    @PostMapping("/handovers/{id}/confirm")
    public ApiResponse<HandoverOrder> confirm(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_SIGN_MAINT);
        return ApiResponse.ok(handoverService.confirm(id, body, empId(req)));
    }

    /** 仅节点授权角色可签字。 */
    @PostMapping("/handovers/{id}/sign")
    public ApiResponse<HandoverSign> sign(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        return ApiResponse.ok(handoverService.signNode(id, body, empId(request)));
    }

    @PostMapping("/handovers/{id}/review")
    public ApiResponse<HandoverOrder> review(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        String action = body == null || body.get("action") == null ? "" : String.valueOf(body.get("action")).trim().toUpperCase();
        if ("REJECT".equals(action)) {
            com.cqwlw.maintenance.auth.MultiRoleGate.requireWrite(request, com.cqwlw.maintenance.auth.PermissionMatrix.HANDOVER_REVIEW_RETURN);
        } else {
            com.cqwlw.maintenance.auth.MultiRoleGate.requireWrite(request, com.cqwlw.maintenance.auth.PermissionMatrix.HANDOVER_REVIEW);
        }
        return ApiResponse.ok(handoverService.review(id, body, empId(request)));
    }

    @PostMapping("/handovers/{id}/void")
    public ApiResponse<HandoverOrder> voidHandover(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        com.cqwlw.maintenance.auth.MultiRoleGate.requireWrite(request, com.cqwlw.maintenance.auth.PermissionMatrix.HANDOVER_VOID);
        return ApiResponse.ok(handoverService.voidHandover(id, body, empId(request)));
    }

    private String empId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID));
    }
}
