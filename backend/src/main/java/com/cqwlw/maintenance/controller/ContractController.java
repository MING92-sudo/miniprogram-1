package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
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
 * 权限按能力码判（docs/14 §6）：读=contract:read（含安装经理/维保经理/商务），写=contract:write（商务+平台层）。
 * 项目合同台账需要跨部门查阅，安装部保留读权限，不使用 requireAdmin 一刀切。
 * /contracts/** 不在 AdminRoleInterceptor 覆盖清单，故在本控制器内自判（403 且无副作用）。
 */
@RestController
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping("/contracts")
    public ApiResponse<List<MaintenanceContract>> list(HttpServletRequest request) {
        requirePerm(request, PermissionMatrix.CONTRACT_READ, "仅可查看合同");
        return ApiResponse.ok(contractService.list());
    }

    @PostMapping("/contracts")
    public ApiResponse<MaintenanceContract> create(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        requirePerm(request, PermissionMatrix.CONTRACT_WRITE, "仅维保商务可维护合同");
        return ApiResponse.ok(contractService.create(body));
    }

    @GetMapping("/contracts/{id}")
    public ApiResponse<MaintenanceContract> get(@PathVariable String id, HttpServletRequest request) {
        requirePerm(request, PermissionMatrix.CONTRACT_READ, "仅可查看合同");
        return ApiResponse.ok(contractService.get(id));
    }

    private static void requirePerm(HttpServletRequest request, String capability, String message) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        if (!PermissionMatrix.allows(role, capability)) {
            throw new BizException(403, message);
        }
    }
}
