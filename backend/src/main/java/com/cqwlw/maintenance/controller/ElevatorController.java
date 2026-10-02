package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(elevatorService.listView());
    }

    @GetMapping("/elevators/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable String id) {
        return ApiResponse.ok(elevatorService.profile(id));
    }

    /** 手动派单（无首次维保时间的电梯由管理员在此触发，跳过到期检查） */
    @PostMapping("/elevators/{id}/dispatch")
    public ApiResponse<WorkOrder> dispatchNow(@PathVariable String id) {
        return ApiResponse.ok(dispatchService.dispatchNow(id));
    }
}