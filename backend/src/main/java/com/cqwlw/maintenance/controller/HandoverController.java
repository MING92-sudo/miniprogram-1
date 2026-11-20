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

/**
 * 移交单（W1，Task 4）端点。
 * /handovers/** 不在 AdminRoleInterceptor 覆盖清单，读端点走 MultiRoleGate.requireRead(HANDOVER_VIEW)；
 * 创建（init）走 requireWrite(HANDOVER_CREATE)（2026-11-21 对齐矩阵 §4.1：仅 INST_PM，越权 403 无副作用）。
 */
@RestController
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    /** 安装部发起移交（进入 DOC_SUBMITTING）；handover:create 仅 INST_PM（矩阵 §4.1） */
    @PostMapping("/handovers")
    public ApiResponse<HandoverOrder> init(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_CREATE);
        return ApiResponse.ok(handoverService.init(body));
    }

    /** 管理端列表（Task 14）：status 过滤 + 分页；部门互斥读门禁（2026-11-15 对齐权限面） */
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
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.submitDocs(id, body));
    }

    /** 维保部经理分配接收工程师 */
    @PostMapping("/handovers/{id}/assign-receiver")
    public ApiResponse<HandoverOrder> assignReceiver(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.assignReceiver(id, body));
    }

    /** 现场查验（安全装置/制动器不合格 → 整改中） */
    @PostMapping("/handovers/{id}/inspect")
    public ApiResponse<HandoverOrder> submitInspect(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.submitInspect(id, body));
    }

    /** 安装部上传整改前后照片 */
    @PostMapping("/handovers/{id}/rectify")
    public ApiResponse<HandoverOrder> submitRectify(@PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.submitRectify(id, body));
    }

    /** 复验（通过回待确认） */
    @PostMapping("/handovers/{id}/recheck-rectify")
    public ApiResponse<HandoverOrder> recheckRectify(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.recheckRectify(id, body));
    }

    /** 经理确认 → 已归档（归档副作用见 Task 5） */
    @PostMapping("/handovers/{id}/confirm")
    public ApiResponse<HandoverOrder> confirm(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.confirm(id, body));
    }

    /** 节点账号签字（通过=签字；非该节点授权角色 1403，Task 6） */
    @PostMapping("/handovers/{id}/sign")
    public ApiResponse<HandoverSign> sign(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        return ApiResponse.ok(handoverService.signNode(id, body, empId(request)));
    }

    /** C-13 安装部内部审核：通过→INSPECTING（handover:review）/退回→DOC_SUBMITTING（handover:review_return，原因+期限必填） */
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

    /** C-13 作废：任意未生效态→VOIDED（handover:void，原因必填；安装主管/维保经理/平台） */
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
