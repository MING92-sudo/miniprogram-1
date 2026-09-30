package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.BusinessRecordService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final BusinessRecordService businessRecordService;

    public MessageController(BusinessRecordService businessRecordService) {
        this.businessRecordService = businessRecordService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Map<String, Object> query) {
        return ApiResponse.ok(businessRecordService.messages(query == null ? Map.of() : query));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.ok(Map.of("count", businessRecordService.unreadCount()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable Long id) {
        businessRecordService.markRead(id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead() {
        businessRecordService.markAllRead();
        return ApiResponse.ok(Map.of("ok", true));
    }
}
