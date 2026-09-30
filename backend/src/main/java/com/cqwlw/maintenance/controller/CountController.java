package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 云托管模板示例接口 /api/count（wx.cloud.callContainer 联调用）。
 * 骨架阶段使用内存计数；接入 MySQL 后可改为落库。
 */
@RestController
public class CountController {

    private static final Logger log = LoggerFactory.getLogger(CountController.class);

    private final AtomicInteger counter = new AtomicInteger(0);

    @GetMapping("/api/count")
    public ApiResponse<Integer> get() {
        return ApiResponse.ok(counter.get());
    }

    @PostMapping("/api/count")
    public ApiResponse<Integer> update(@RequestBody Map<String, String> body) {
        String action = body == null ? null : body.get("action");
        log.info("POST /api/count, action: {}", action);
        if ("inc".equals(action)) {
            return ApiResponse.ok(counter.incrementAndGet());
        }
        if ("clear".equals(action)) {
            counter.set(0);
            return ApiResponse.ok(0);
        }
        return ApiResponse.error(400, "参数action错误");
    }
}
