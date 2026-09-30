package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/elevators")
public class ElevatorController {
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final ObjectMapper objectMapper;

    public ElevatorController(ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper, ObjectMapper objectMapper) {
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(elevatorMapper.selectList(null).stream().map(this::toMap).toList());
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable Long id) {
        Elevator elevator = elevatorMapper.selectById(id);
        if (elevator == null) {
            return ApiResponse.error(1404, "电梯不存在");
        }
        return ApiResponse.ok(toMap(elevator));
    }

    private Map<String, Object> toMap(Elevator elevator) {
        Map<String, Object> view = new LinkedHashMap<>(objectMapper.convertValue(elevator, Map.class));
        UseUnit unit = useUnitMapper.selectById(elevator.getUseUnitId());
        if (unit != null) {
            view.put("projectName", unit.getUnitName());
            view.put("unitPrincipal", unit.getUnitPrincipal());
            view.put("unitPrincipalPhone", unit.getUnitPrincipalPhone());
            view.put("elevatorAdminister", unit.getElevatorAdminister());
            view.put("elevatorAdministerPhone", unit.getElevatorAdministerPhone());
            view.put("emergencyPhone", unit.getEmergencyPhone());
        }
        return view;
    }
}
