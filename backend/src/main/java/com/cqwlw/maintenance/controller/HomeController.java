package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.service.WorkOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 首页工作台汇总（docs/04 A.2 /home/summary） */
@RestController
public class HomeController {

    private final WorkOrderService workOrderService;

    public HomeController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping("/home/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        return ApiResponse.ok(workOrderService.homeSummary(
                String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID))));
    }
}
