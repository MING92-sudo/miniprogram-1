package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 电梯列表/档案（docs/04 A.2/A.4.1）+ 手动派单（首保/补单） */
@RestController
public class ElevatorController {

    private final ElevatorService elevatorService;
    private final DispatchService dispatchService;

    public ElevatorController(ElevatorService elevatorService, DispatchService dispatchService) {
        this.elevatorService = elevatorService;
        this.dispatchService = dispatchService;
    }

    @GetMapping("/elevators")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        return ApiResponse.ok(elevatorService.listView(empId(request)));
    }

    /** 扫码回填电梯信息（急修单用，docs/04 A.6） */
    @GetMapping("/elevators/by-code")
    public ApiResponse<Map<String, Object>> byCode(@RequestParam String code) {
        return ApiResponse.ok(elevatorService.viewByCode(code));
    }

    @GetMapping("/elevators/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(elevatorService.profile(id, empId(request)));
    }

    /** 维保人员现场更正项目名称（小区名称，docs/04 A.2.1）；仅本人负责的电梯，越权 1403 */
    @PutMapping("/elevators/{id}/project-name")
    public ApiResponse<Map<String, Object>> updateProjectName(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        Object v = body == null ? null : body.get("projectName");
        elevatorService.updateProjectName(id, empId(request), v == null ? null : String.valueOf(v));
        return ApiResponse.ok(Map.of("ok", true));
    }

    private String empId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID));
    }

    /** 手动派单（无首次维保时间的电梯由管理员在此触发，跳过到期检查） */
    @PostMapping("/elevators/{id}/dispatch")
    public ApiResponse<WorkOrder> dispatchNow(@PathVariable String id) {
        return ApiResponse.ok(dispatchService.dispatchNow(id));
    }
}
