package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.ExternalFilingOrder;
import com.cqwlw.maintenance.entity.HandoverConfirmation;
import com.cqwlw.maintenance.service.ExternalFilingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 外接建档（W2，Task 7）端点。
 * 与移交同口径：/filings/** 不在 AdminRoleInterceptor 覆盖清单，天然只要求已登录；
 * 角色门禁与数据范围谓词待 Task 8（安装部/维保部经理/使用单位各自的写权限）。
 */
@RestController
public class ExternalFilingController {

    private final ExternalFilingService filingService;

    public ExternalFilingController(ExternalFilingService filingService) {
        this.filingService = filingService;
    }

    /** 发起外接建档（校验已生效合同，进入 DOC_COLLECTING） */
    @PostMapping("/filings")
    public ApiResponse<ExternalFilingOrder> init(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(filingService.init(body));
    }

    /** 管理端列表（Task 14）：status 过滤 + 分页 */
    @GetMapping("/filings")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String size) {
        Map<String, String> query = new java.util.HashMap<>();
        query.put("status", status == null ? "" : status);
        query.put("page", page == null ? "" : page);
        query.put("size", size == null ? "" : size);
        return ApiResponse.ok(filingService.list(query));
    }

    /** 外接建档单详情 */
    @GetMapping("/filings/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        return ApiResponse.ok(filingService.detail(id));
    }

    /** 提交建档资料（可标缺件待补办，提交后进入 TAKEOVER_CHECKING） */
    @PostMapping("/filings/{id}/submit-docs")
    public ApiResponse<ExternalFilingOrder> submitDocs(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(filingService.submitDocs(id, body));
    }

    /** 提交接管查验（不合格不阻塞，责任归属必填，提交后进入 PENDING_CONFIRM） */
    @PostMapping("/filings/{id}/takeover-check")
    public ApiResponse<ExternalFilingOrder> takeoverCheck(@PathVariable String id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(filingService.submitTakeoverCheck(id, body));
    }

    /** 交接确认（双方签字）→ 归档建电梯 + 首保工单 */
    @PostMapping("/filings/{id}/confirm")
    public ApiResponse<HandoverConfirmation> confirm(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(filingService.confirm(id, body));
    }
}
