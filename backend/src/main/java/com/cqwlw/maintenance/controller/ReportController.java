package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.PlatformClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维保数据上报入口（前端 services/order.js、fault.js 等对应）。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final PlatformClient platformClient;

    public ReportController(PlatformClient platformClient) {
        this.platformClient = platformClient;
    }

    @PostMapping
    public ApiResponse<String> report(Object payload) {
        // TODO: 校验 + 落库留痕 + 调 platformClient.forward 转发平台
        return ApiResponse.error(501, "上报接口尚未实现");
    }
}
