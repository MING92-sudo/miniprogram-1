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

/**
 * 工单与现场作业（docs/04 A.2）：签到/双人动态码/清单/签退均带幂等键去重（AGENTS §3）；
 * 详情与现场作业端点一律把登录人 empId 交给服务层校验工单归属（V7 班组数据权限，AGENTS §6）。
 */
@RestController
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final IdempotencyService idempotencyService;

    public WorkOrderController(WorkOrderService workOrderService, IdempotencyService idempotencyService) {
        this.workOrderService = workOrderService;
        this.idempotencyService = idempotencyService;
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

    // Minor7：只读查询改 GET，不再占用幂等表
    @GetMapping("/work-orders/resolve-by-elevator")
    public ApiResponse<Object> resolveByElevator(@RequestParam String elevatorCode,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.resolveByElevator(elevatorCode, empId(request)));
    }

    @PostMapping("/work-orders/{id}/checkin")
    public ApiResponse<Object> checkin(@PathVariable String id, @RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                       HttpServletRequest request) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checkin");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.checkin(id, body, empId(request));
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    /** 主维保生成双人动态码（60 秒有效、绑定工单、一次性；docs/03 §3.2 项6） */
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

    @GetMapping("/work-orders/{id}/checklist")
    public ApiResponse<Map<String, Object>> checklist(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.getChecklist(id, empId(request)));
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}")
    public ApiResponse<Object> submitItem(@PathVariable String id, @PathVariable String itemId,
                                          @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                          HttpServletRequest request) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checklist-item");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.submitItem(id, itemId, body, empId(request));
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}/run-this-time")
    public ApiResponse<Map<String, Object>> runThisTime(@PathVariable String id, @PathVariable String itemId,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.runThisTime(id, itemId, empId(request)));
    }

    @PostMapping("/work-orders/{id}/checkout")
    public ApiResponse<Object> checkout(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                        HttpServletRequest request) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checkout");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.checkout(id, body == null ? Map.of() : body, empId(request));
        guard.commit(result);
        return ApiResponse.ok(result);
    }

}
