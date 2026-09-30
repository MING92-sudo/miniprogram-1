package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.BusinessRecordService;
import com.cqwlw.maintenance.security.AuthContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BusinessRecordController {
    private final BusinessRecordService businessRecordService;
    private final AuthContext authContext;

    public BusinessRecordController(BusinessRecordService businessRecordService, AuthContext authContext) {
        this.businessRecordService = businessRecordService;
        this.authContext = authContext;
    }

    @GetMapping("/rescues")
    public ApiResponse<Map<String, Object>> rescues(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(businessRecordService.list("RESCUE", query == null ? Map.of() : query));
    }

    @PostMapping("/rescues")
    public ApiResponse<Map<String, Object>> createRescue(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(businessRecordService.create("RESCUE", body, authContext.currentPhone()));
    }

    @GetMapping("/rescues/{id}")
    public ApiResponse<Map<String, Object>> rescueDetail(@PathVariable Long id) {
        return ApiResponse.ok(businessRecordService.detail("RESCUE", id));
    }

    @GetMapping("/faults")
    public ApiResponse<Map<String, Object>> faults(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(businessRecordService.list("FAULT", query == null ? Map.of() : query));
    }

    @PostMapping("/faults")
    public ApiResponse<Map<String, Object>> createFault(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(businessRecordService.create("FAULT", body, authContext.currentPhone()));
    }

    @GetMapping("/faults/{id}")
    public ApiResponse<Map<String, Object>> faultDetail(@PathVariable Long id) {
        return ApiResponse.ok(businessRecordService.detail("FAULT", id));
    }

    @PostMapping("/faults/{id}/close")
    public ApiResponse<Map<String, Object>> closeFault(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> patch = body == null ? Map.of() : body;
        return ApiResponse.ok(businessRecordService.update("FAULT", id, patch));
    }

    @GetMapping("/drills")
    public ApiResponse<Map<String, Object>> drills(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(businessRecordService.list("DRILL", query == null ? Map.of() : query));
    }

    @PostMapping("/drills")
    public ApiResponse<Map<String, Object>> createDrill(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(businessRecordService.create("DRILL", body, authContext.currentPhone()));
    }

    @GetMapping("/inspects")
    public ApiResponse<Map<String, Object>> inspects(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(businessRecordService.list("INSPECT", query == null ? Map.of() : query));
    }

    @PostMapping("/inspects")
    public ApiResponse<Map<String, Object>> createInspect(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(businessRecordService.create("INSPECT", body, authContext.currentPhone()));
    }

    @GetMapping("/inspects/template")
    public ApiResponse<Map<String, Object>> inspectTemplate() {
        return ApiResponse.ok(Map.of("items", java.util.List.of()));
    }
}
