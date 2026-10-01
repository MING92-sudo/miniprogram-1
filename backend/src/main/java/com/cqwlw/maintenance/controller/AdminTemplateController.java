package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端·检查项模板管理：
 * GET 列表（OFFICIAL 257 行只读 + CUSTOM）；POST/PUT/启停仅 CUSTOM。
 */
@RestController
public class AdminTemplateController {

    private final AdminTemplateService templateService;

    public AdminTemplateController(AdminTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping("/admin/templates")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String templateType,
            @RequestParam(required = false) String appendix,
            @RequestParam(required = false) String categoryScope,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(templateService.listTemplates(Map.of(
                "templateType", nz(templateType), "appendix", nz(appendix),
                "categoryScope", nz(categoryScope), "keyword", nz(keyword),
                "page", nz(page), "size", nz(size))));
    }

    @PostMapping("/admin/templates")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.createTemplate(body));
    }

    @PutMapping("/admin/templates/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.updateTemplate(id, body));
    }

    @PutMapping("/admin/templates/{id}/enabled")
    public ApiResponse<Map<String, Object>> setEnabled(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.setEnabled(id,
                Boolean.parseBoolean(String.valueOf(body.get("enabled")))));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
