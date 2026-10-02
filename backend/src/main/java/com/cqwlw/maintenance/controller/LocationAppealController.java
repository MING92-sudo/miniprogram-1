package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.ApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 小程序端·定位异常申述（1001 定位超阈 → POST /workorders/{id}/location-appeal） */
@RestController
public class LocationAppealController {

    private final ApprovalService approvalService;

    public LocationAppealController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/work-orders/{id}/location-appeal")
    public ApiResponse<Map<String, Object>> submit(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        return ApiResponse.ok(approvalService.submit(id, body, attr == null ? null : String.valueOf(attr)));
    }
}
