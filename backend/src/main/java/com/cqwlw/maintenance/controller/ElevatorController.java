package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ElevatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ElevatorController {

    private final ElevatorService elevatorService;
    private final DispatchService dispatchService;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;

    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    public ElevatorController(ElevatorService elevatorService, DispatchService dispatchService,
                              com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.elevatorService = elevatorService;
        this.dispatchService = dispatchService;
        this.userRoleMapper = userRoleMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    private String unitAnchor(HttpServletRequest request) {
        Object principalType = request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_PRINCIPAL_TYPE);
        if (com.cqwlw.maintenance.auth.RoleCatalog.PrincipalType.CUSTOMER.name()
                .equals(String.valueOf(principalType)) && clientAccountMapper != null) {
            Object accountId = request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID);
            if (accountId != null) {
                com.cqwlw.maintenance.entity.ClientAccount account =
                        clientAccountMapper.selectById(String.valueOf(accountId));
                if (account != null && account.useUnitId != null) {
                    return account.useUnitId;
                }
            }
        }
        com.cqwlw.maintenance.entity.SysUserRole row = userRoleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId, empId(request))
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE")
                        .likeRight(com.cqwlw.maintenance.entity.SysUserRole::getRoleCode, "CLIENT")
                        .last("LIMIT 1"));
        return row == null ? null : row.orgId;
    }

    private boolean isClient(HttpServletRequest request) {
        return com.cqwlw.maintenance.auth.MultiRoleGate.roles(request).stream()
                .anyMatch(r -> r != null && r.startsWith("CLIENT"));
    }

    private void requireUnit(HttpServletRequest request, String elevatorId) {
        if (!isClient(request)) {
            return;
        }
        com.cqwlw.maintenance.entity.Elevator el = elevatorService.get(elevatorId);
        String anchor = unitAnchor(request);
        if (el == null || anchor == null || !anchor.equals(el.useUnitId)) {
            throw new com.cqwlw.maintenance.common.BizException(1403, "无权查看其他使用单位电梯");
        }
    }

    @GetMapping("/elevators")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (isClient(request)) {
            return ApiResponse.ok(elevatorService.listViewForUnit(unitAnchor(request)));
        }
        return ApiResponse.ok(elevatorService.listView(empId(request)));
    }

    @GetMapping("/elevators/by-code")
    public ApiResponse<Map<String, Object>> byCode(@RequestParam String code, HttpServletRequest request) {
        Map<String, Object> row = elevatorService.viewByCode(code);
        if (isClient(request) && row != null) {
            requireUnit(request, String.valueOf(row.get("id")));
        }
        return ApiResponse.ok(row);
    }

    @GetMapping("/elevators/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable String id, HttpServletRequest request) {
        if (isClient(request)) {
            requireUnit(request, id);
            return ApiResponse.ok(elevatorService.profileForUnit(unitAnchor(request), id));
        }
        return ApiResponse.ok(elevatorService.profile(id, empId(request)));
    }

    /** 仅本人负责的电梯，越权 1403 */
    @PutMapping("/elevators/{id}/project-name")
    public ApiResponse<Map<String, Object>> updateProjectName(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        Object v = body == null ? null : body.get("projectName");
        elevatorService.updateProjectName(id, empId(request), v == null ? null : String.valueOf(v));
        return ApiResponse.ok(Map.of("ok", true));
    }

    /** 仅本人负责的电梯，越权 1403 */
    @PutMapping("/elevators/{id}/local")
    public ApiResponse<Map<String, Object>> updateLocal(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        elevatorService.updateLocal(id, empId(request), body);
        return ApiResponse.ok(Map.of("ok", true));
    }

    private String empId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID));
    }

    @PostMapping("/elevators/{id}/dispatch")
    public ApiResponse<WorkOrder> dispatchNow(@PathVariable String id) {
        return ApiResponse.ok(dispatchService.dispatchNow(id));
    }
}
