package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.WorkOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 工单与现场作业：签到/双人动态码/清单/签退均带幂等键去重（AGENTS §3）； */
@RestController
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final IdempotencyService idempotencyService;

    public WorkOrderController(WorkOrderService workOrderService, IdempotencyService idempotencyService,
                               com.cqwlw.maintenance.service.RecordLifecycleService recordLifecycleService) {
        this.workOrderService = workOrderService;
        this.idempotencyService = idempotencyService;
        this.recordLifecycleService = recordLifecycleService;
    }

    private final com.cqwlw.maintenance.service.RecordLifecycleService recordLifecycleService;

    /** 配合人退回签字（仅配合人本人，无上报副作用） */
    @PostMapping("/work-orders/{id}/signature-refuse")
    public ApiResponse<Object> signatureRefuse(@PathVariable String id,
                                               @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                               HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, Map.of(), request,
                () -> recordLifecycleService.signatureRefuse(id, empId(request))));
    }

    @PostMapping("/work-orders/{id}/review")
    public ApiResponse<Object> review(@PathVariable String id, @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                      HttpServletRequest request) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> recordLifecycleService.review(id, pass, reason,
                        com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), empId(request))));
    }

    @PostMapping("/work-orders/{id}/sign-request")
    public ApiResponse<Object> signRequest(@PathVariable String id,
                                           @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, Map.of(), request,
                () -> recordLifecycleService.signRequest(id,
                        com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), empId(request))));
    }

    @GetMapping("/work-orders/{id}/archive")
    public ApiResponse<Map<String, Object>> archive(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(recordLifecycleService.archiveView(id, empId(request)));
    }

    @GetMapping("/work-orders")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String size,
                                                 @RequestParam(required = false) String due,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 HttpServletRequest request) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size,
                "due", due == null ? "" : due,
                "status", status == null ? "" : status,
                "keyword", keyword == null ? "" : keyword);
        return ApiResponse.ok(workOrderService.listOrders(query, empId(request)));
    }

    @GetMapping("/work-orders/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.getOrderView(id, empId(request)));
    }

    private String empId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID));
    }

    @GetMapping("/work-orders/resolve-by-elevator")
    public ApiResponse<Object> resolveByElevator(@RequestParam String elevatorCode,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.resolveByElevator(elevatorCode, empId(request)));
    }

    @PostMapping("/work-orders/{id}/checkin")
    public ApiResponse<Object> checkin(@PathVariable String id, @RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                       HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> workOrderService.checkin(id, body, empId(request))));
    }

    /** 主维保生成双人动态码（60 秒有效、绑定工单、一次性； */
    @PostMapping("/work-orders/{id}/dynamic-code")
    public ApiResponse<Map<String, Object>> issueDynamicCode(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.issueDynamicCode(id, empId(request)));
    }

    @PostMapping("/work-orders/{id}/dynamic-code/verify")
    public ApiResponse<Map<String, Object>> verifyDynamicCode(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.verifyDynamicCode(id, body, empId(request)));
    }

    /** 仅主维保本人） */
    @GetMapping("/work-orders/{id}/partner-candidates")
    public ApiResponse<Map<String, Object>> partnerCandidates(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.partnerCandidates(id, empId(request)));
    }

    /** 指派/改选配合人（仅主维保本人，配合人未签到前可改选；写操作带幂等键去重） */
    @PostMapping("/work-orders/{id}/assign-partner")
    public ApiResponse<Object> assignPartner(@PathVariable String id, @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                             HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> workOrderService.assignPartner(id, body, empId(request))));
    }

    @GetMapping("/work-orders/{id}/checklist")
    public ApiResponse<Map<String, Object>> checklist(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.getChecklist(id, empId(request)));
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}")
    public ApiResponse<Object> submitItem(@PathVariable String id, @PathVariable String itemId,
                                          @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                          HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> workOrderService.submitItem(id, itemId, body, empId(request))));
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}/run-this-time")
    public ApiResponse<Map<String, Object>> runThisTime(@PathVariable String id, @PathVariable String itemId,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.runThisTime(id, itemId, empId(request)));
    }

    /** 主维保/配合人员各自手机签字：写操作带幂等键去重 */
    @PostMapping("/work-orders/{id}/signature")
    public ApiResponse<Object> signOrder(@PathVariable String id, @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                         HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> workOrderService.signOrder(id, body, empId(request))));
    }

    @PostMapping("/work-orders/{id}/checkout")
    public ApiResponse<Object> checkout(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                        HttpServletRequest request) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, safeBody, request,
                () -> workOrderService.checkout(id, safeBody, empId(request))));
    }

}
