package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.service.UnitPortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 使用单位门户（W3，Task 10）：数据域一律收敛到本单位 useUnitId，不接受请求体指定单位。
 * M1 统一令牌（V41，2026-11-16 审查修复）：员工令牌按 CLIENT 角色锚点（sys_user_role.org_id）
 * 解析单位，与 UnitRecordController 同口径；旧门户令牌（ut=1，ATTR_UNIT_ID）兜底至生产清库；
 * 无 CLIENT 角色/无锚点 fail-closed 403。报修复用急修链路（POST /unit/faults）。
 */
@RestController
public class UnitPortalController {

    private final UnitPortalService portalService;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;

    public UnitPortalController(UnitPortalService portalService,
                                com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.portalService = portalService;
        this.userRoleMapper = userRoleMapper;
    }

    /** 本单位电梯 */
    @GetMapping("/unit/elevators")
    public ApiResponse<List<Map<String, Object>>> elevators(HttpServletRequest request) {
        return ApiResponse.ok(portalService.elevators(unitId(request)));
    }

    /** 本单位维保记录 */
    @GetMapping("/unit/records/list")
    public ApiResponse<List<Map<String, Object>>> records(HttpServletRequest request) {
        return ApiResponse.ok(portalService.records(unitId(request)));
    }

    /** 待确认签字的记录 */
    @GetMapping("/unit/pending-signs")
    public ApiResponse<List<Map<String, Object>>> pendingSigns(HttpServletRequest request) {
        return ApiResponse.ok(portalService.pendingSigns(unitId(request)));
    }

    /** 本单位急修单 */
    @GetMapping("/unit/faults")
    public ApiResponse<List<Map<String, Object>>> faults(HttpServletRequest request) {
        return ApiResponse.ok(portalService.faults(unitId(request)));
    }

    /** 使用单位报修（从本单位电梯选，派给该梯责任人） */
    @PostMapping("/unit/faults")
    public ApiResponse<Map<String, Object>> reportFault(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(portalService.reportFault(unitId(request), body));
    }

    /** 单位身份：旧门户令牌优先；员工令牌取 CLIENT 角色锚点 org_id；无锚点 fail-closed 403 */
    private String unitId(HttpServletRequest request) {
        Object portal = request.getAttribute(AuthInterceptor.ATTR_UNIT_ID);
        if (portal != null) {
            return String.valueOf(portal);
        }
        com.cqwlw.maintenance.entity.SysUserRole row = userRoleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId,
                                String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID)))
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE")
                        .likeRight(com.cqwlw.maintenance.entity.SysUserRole::getRoleCode, "CLIENT")
                        .last("LIMIT 1"));
        if (row == null || row.orgId == null || row.orgId.isBlank()) {
            throw new BizException(403, "当前账号无使用单位数据权限");
        }
        return row.orgId;
    }
}
