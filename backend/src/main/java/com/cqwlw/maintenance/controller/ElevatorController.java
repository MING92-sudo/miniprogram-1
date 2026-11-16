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

/** 电梯列表/档案（docs/04 A.2/A.4.1）+ 手动派单（首保/补单）；CLIENT 账号收敛本单位（2026-11-15 裁决）。 */
@RestController
public class ElevatorController {

    private final ElevatorService elevatorService;
    private final DispatchService dispatchService;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;

    public ElevatorController(ElevatorService elevatorService, DispatchService dispatchService,
                              com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.elevatorService = elevatorService;
        this.dispatchService = dispatchService;
        this.userRoleMapper = userRoleMapper;
    }

    /** CLIENT 角色的组织锚点（sys_user_role.org_id = use_unit.id），与 DirectoryController 同口径 */
    private String unitAnchor(HttpServletRequest request) {
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

    /** 扫码回填电梯信息（急修单用，docs/04 A.6） */
    @GetMapping("/elevators/by-code")
    public ApiResponse<Map<String, Object>> byCode(@RequestParam String code, HttpServletRequest request) {
        Map<String, Object> row = elevatorService.viewByCode(code);
        if (isClient(request) && row != null) {
            requireUnit(request, String.valueOf(row.get("id")));
        }
        return ApiResponse.ok(row);
    }

    /** 档案（docs/04 A.12.1①）：客户走本单位谓词（P1 终审 I1，worker-scope 对客户恒 1403 与契约矛盾）；员工保持班组谓词 */
    @GetMapping("/elevators/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable String id, HttpServletRequest request) {
        if (isClient(request)) {
            requireUnit(request, id);
            return ApiResponse.ok(elevatorService.profileForUnit(unitAnchor(request), id));
        }
        return ApiResponse.ok(elevatorService.profile(id, empId(request)));
    }

    /** 维保人员现场更正项目名称（小区名称，docs/04 A.2.1）；仅本人负责的电梯，越权 1403 */
    @PutMapping("/elevators/{id}/project-name")
    public ApiResponse<Map<String, Object>> updateProjectName(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        Object v = body == null ? null : body.get("projectName");
        elevatorService.updateProjectName(id, empId(request), v == null ? null : String.valueOf(v));
        return ApiResponse.ok(Map.of("ok", true));
    }

    /** 维保人员现场维护设备本地信息（docs/04 A.2.1）；仅本人负责的电梯，越权 1403 */
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

    /** 手动派单（无首次维保时间的电梯由管理员在此触发，跳过到期检查） */
    @PostMapping("/elevators/{id}/dispatch")
    public ApiResponse<WorkOrder> dispatchNow(@PathVariable String id) {
        return ApiResponse.ok(dispatchService.dispatchNow(id));
    }
}
