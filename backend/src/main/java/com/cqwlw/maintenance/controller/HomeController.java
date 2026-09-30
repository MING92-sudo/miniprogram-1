package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.BusinessRecordService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HomeController {
    private final BusinessRecordService businessRecordService;

    public HomeController(BusinessRecordService businessRecordService) {
        this.businessRecordService = businessRecordService;
    }

    @GetMapping("/home/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(businessRecordService.summary());
    }
}
