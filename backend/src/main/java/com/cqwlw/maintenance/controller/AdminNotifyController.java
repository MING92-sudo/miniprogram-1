package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.NotifyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理端·订阅消息发送记录（docs/04 A.7；真实推送云端后置，本地落记录） */
@RestController
public class AdminNotifyController {

    private final NotifyService notifyService;

    public AdminNotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @GetMapping("/admin/notify-records")
    public ApiResponse<Map<String, Object>> records(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(notifyService.listRecords(Map.of(
                "type", nz(type), "channel", nz(channel), "status", nz(status),
                "page", nz(page), "size", nz(size))));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
