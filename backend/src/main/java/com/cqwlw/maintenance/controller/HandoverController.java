package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.service.HandoverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 移交单（W1，Task 4）端点。
 * 角色门禁待 Task 8（INSTALLER/USE_UNIT 角色 + 端点级门禁 + 数据范围谓词）：
 * 移交涉及安装部/维保部经理/接收工程师三类人，无一 ADMIN-only，故本任务不强加 requireAdmin；
 * /handovers/** 不在 AdminRoleInterceptor 覆盖清单，天然只要求已登录（AuthInterceptor 默认）。
 * 状态机逻辑本身与角色无关，Task 8 补齐角色与数据范围后再行收敛。
 */
@RestController
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    /** 安装部发起移交（进入 DOC_SUBMITTING） */
    @PostMapping("/handovers")
    public ApiResponse<HandoverOrder> init(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(handoverService.init(body));
    }

    /** 移交单详情 */
    @GetMapping("/handovers/{id}")
    public ApiResponse<HandoverOrder> get(@PathVariable String id) {
        return ApiResponse.ok(handoverService.get(id));
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
}
