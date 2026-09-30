package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {
    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(workOrderService.list(query == null ? Map.of() : query));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(workOrderService.detail(id));
    }

    @PostMapping("/resolve-by-elevator")
    public ApiResponse<Map<String, Object>> resolve(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.resolveByElevator(String.valueOf(body.get("elevatorCode"))));
    }

    @PostMapping("/{id}/checkin")
    public ApiResponse<Map<String, Object>> checkin(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.checkin(id, body == null ? Map.of() : body));
    }

    @PostMapping("/{id}/dynamic-code/verify")
    public ApiResponse<Map<String, Object>> verifyDynamicCode(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.verifyDynamicCode(id, body));
    }

    @GetMapping("/{id}/checklist")
    public ApiResponse<Map<String, Object>> checklist(@PathVariable Long id) {
        return ApiResponse.ok(workOrderService.checklist(id));
    }

    @PostMapping("/{id}/checklist/{itemId}")
    public ApiResponse<Map<String, Object>> submitItem(@PathVariable Long id, @PathVariable String itemId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.submitItem(id, itemId, body == null ? Map.of() : body));
    }

    @PostMapping("/{id}/checklist/{itemId}/run-this-time")
    public ApiResponse<Map<String, Object>> runItemThisTime(@PathVariable Long id, @PathVariable String itemId) {
        return ApiResponse.ok(workOrderService.runItemThisTime(id, itemId));
    }

    @PostMapping("/{id}/checkout")
    public ApiResponse<Map<String, Object>> checkout(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workOrderService.checkout(id, body == null ? Map.of() : body));
    }
}
