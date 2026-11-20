package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.service.RegionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 区域主数据 CRUD + 电梯区域分配（Task 1，移交/外接建档/使用单位门户可见性范围）。
 * 全部端点仅管理员（ADMIN/SYS_ADMIN）可用，非管理员 403、无副作用。
 * 注：/elevators/{id}/region 同时受 AdminRoleInterceptor 管理端写门禁覆盖，
 * 非管理员会先被拦截器以 403 拦截；此处 requireAdmin 为兜底（同为 403，口径一致）。
 * 员工所属区域不设专职端点：随 POST/PUT /employees 建档/编辑一并维护（D5，regionId 可空）。
 */
@RestController
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    @GetMapping("/regions")
    public ApiResponse<List<Region>> list(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(regionService.list());
    }

    @PostMapping("/regions")
    public ApiResponse<Region> create(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(regionService.create(body));
    }

    @PutMapping("/regions/{id}")
    public ApiResponse<Region> update(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(regionService.update(id, body));
    }

    @DeleteMapping("/regions/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        regionService.delete(id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PutMapping("/elevators/{id}/region")
    public ApiResponse<Map<String, Object>> assignElevatorRegion(@PathVariable String id,
                                                                 @RequestBody(required = false) Map<String, Object> body,
                                                                 HttpServletRequest request) {
        requireAdmin(request);
        regionService.assignElevatorRegion(id, str(body, "regionId"));
        return ApiResponse.ok(Map.of("ok", true));
    }

    private static void requireAdmin(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        if (!AdminRoles.canWrite(role)) {
            throw new BizException(403, "仅管理员可维护区域");
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
