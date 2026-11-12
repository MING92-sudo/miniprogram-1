package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.service.ContractService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 维保合同（最小版，Task 3）：建合同 + 合同列表/详情。
 * 全部端点仅管理员（ADMIN/SYS_ADMIN）可用；/contracts/** 不在 AdminRoleInterceptor 覆盖清单，
 * 故自建 requireAdmin（非管理员 403、无副作用）。
 */
@RestController
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping("/contracts")
    public ApiResponse<List<MaintenanceContract>> list(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(contractService.list());
    }

    @PostMapping("/contracts")
    public ApiResponse<MaintenanceContract> create(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(contractService.create(body));
    }

    @GetMapping("/contracts/{id}")
    public ApiResponse<MaintenanceContract> get(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(contractService.get(id));
    }

    private static void requireAdmin(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        if (!AdminRoles.canWrite(role)) {
            throw new BizException(403, "仅管理员可维护合同");
        }
    }
}
