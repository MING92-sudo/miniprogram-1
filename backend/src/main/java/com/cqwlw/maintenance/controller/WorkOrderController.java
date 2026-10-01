package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.MaintainRecord;
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
 * 工单与现场作业：签到/双人动态码/清单/签退均带幂等键去重。
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
                                                 @RequestParam(required = false) String keyword) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size,
                "due", due == null ? "" : due,
                "status", status == null ? "" : status,
                "keyword", keyword == null ? "" : keyword);
        return ApiResponse.ok(workOrderService.listOrders(query));
    }

    @GetMapping("/work-orders/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResponse.ok(workOrderService.getOrderView(id));
    }

    @PostMapping("/work-orders/resolve-by-elevator")
    public ApiResponse<Object> resolveByElevator(@RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/resolve-by-elevator");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.resolveByElevator(str(body.get("elevatorCode")));
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/work-orders/{id}/evidence")
    public ApiResponse<Map<String, Object>> evidence(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.issueEvidence(id, body == null ? Map.of() : body));
    }

    @PostMapping("/work-orders/{id}/evidence/shot")
    public ApiResponse<Map<String, Object>> shotEvidence(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.issueShotEvidence(id, body == null ? Map.of() : body));
    }

    @PostMapping("/work-orders/{id}/evidence/sign")
    public ApiResponse<Map<String, Object>> signEvidence(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.issueSignEvidence(id, body == null ? Map.of() : body));
    }

    @PostMapping("/work-orders/{id}/checkin")
    public ApiResponse<Object> checkin(@PathVariable String id, @RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checkin");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.checkin(id, body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/work-orders/{id}/dynamic-code/verify")
    public ApiResponse<Map<String, Object>> verifyDynamicCode(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.verifyDynamicCode(body));
    }

    @GetMapping("/work-orders/{id}/checklist")
    public ApiResponse<Map<String, Object>> checklist(@PathVariable String id) {
        return ApiResponse.ok(workOrderService.getChecklist(id));
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}")
    public ApiResponse<Object> submitItem(@PathVariable String id, @PathVariable String itemId,
                                          @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checklist-item");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = workOrderService.submitItem(id, itemId, body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/work-orders/{id}/checklist/{itemId}/run-this-time")
    public ApiResponse<Map<String, Object>> runThisTime(@PathVariable String id, @PathVariable String itemId) {
        return ApiResponse.ok(workOrderService.runThisTime(id, itemId));
    }

    @PostMapping("/work-orders/{id}/checkout")
    public ApiResponse<Object> checkout(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/work-orders/checkout");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        // 先在事务内落库（工单 DONE + 维保记录），事务提交后再转发平台——
// 对外 HTTPS 调用不放进事务，避免其超时/异常导致维保记录被回滚（见 WorkOrderService.reportAfterCheckout）
    MaintainRecord rec = workOrderService.checkout(id, body == null ? Map.of() : body);
        Map<String, Object> result = workOrderService.reportAfterCheckout(id, rec);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
