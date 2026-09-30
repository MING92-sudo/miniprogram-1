package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.PlatformSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台档案同步（P2 验收：2.2/2.7 从平台拉回真实数据落库）。
 * 写操作仅落本地库，不向平台产生数据（2.3/2.4/2.6 属 P3）。
 */
@RestController
public class PlatformController {

    private final PlatformSyncService platformSyncService;

    public PlatformController(PlatformSyncService platformSyncService) {
        this.platformSyncService = platformSyncService;
    }

    @PostMapping("/platform/sync")
    public ApiResponse<Map<String, Object>> sync() {
        return ApiResponse.ok(platformSyncService.syncAll());
    }
}
