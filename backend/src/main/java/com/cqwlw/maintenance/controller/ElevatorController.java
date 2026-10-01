package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.ElevatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 电梯列表/档案 */
@RestController
public class ElevatorController {

    private final ElevatorService elevatorService;

    public ElevatorController(ElevatorService elevatorService) {
        this.elevatorService = elevatorService;
    }

    @GetMapping("/elevators")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(elevatorService.listView());
    }

    @GetMapping("/elevators/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable String id) {
        return ApiResponse.ok(elevatorService.profile(id));
    }
}
