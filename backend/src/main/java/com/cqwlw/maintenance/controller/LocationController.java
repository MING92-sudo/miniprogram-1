package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.LocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** LBS 逆地址解析代理：key 只在后端环境变量，前端不接触 key（docs/08 审查 #5） */
@RestController
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/location/reverse")
    public ApiResponse<Map<String, Object>> reverse(@RequestParam double lng, @RequestParam double lat) {
        return ApiResponse.ok(locationService.reverse(lng, lat));
    }
}
