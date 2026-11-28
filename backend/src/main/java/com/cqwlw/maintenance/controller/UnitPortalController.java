package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.service.UnitPortalService;
import com.cqwlw.maintenance.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 旧门户令牌（ut=1，ATTR_UNIT_ID）兜底至生产清库； */
@RestController
public class UnitPortalController {

    private final UnitPortalService portalService;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;
    private IdempotencyService idempotencyService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdempotencyService(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    private com.cqwlw.maintenance.service.ClientMessageService clientMessageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientMessageService(com.cqwlw.maintenance.service.ClientMessageService clientMessageService) {
        this.clientMessageService = clientMessageService;
    }

    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    private com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmployeeMapper(com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this.employeeMapper = employeeMapper;
    }

    private com.cqwlw.maintenance.mapper.UseUnitMapper useUnitMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUseUnitMapper(com.cqwlw.maintenance.mapper.UseUnitMapper useUnitMapper) {
        this.useUnitMapper = useUnitMapper;
    }

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

    @GetMapping("/unit/faults/{id}")
    public ApiResponse<Map<String, Object>> faultDetail(@org.springframework.web.bind.annotation.PathVariable String id,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(portalService.faultDetail(unitId(request), id));
    }

    @PostMapping("/unit/faults/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirmFault(@org.springframework.web.bind.annotation.PathVariable String id,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request,
                                                         @org.springframework.web.bind.annotation.RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        boolean pass = body == null || !Boolean.FALSE.equals(body.get("pass"));
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return withIdempotency(idemKey, body, request, () ->
                portalService.confirmFault(id, com.cqwlw.maintenance.auth.MultiRoleGate.roles(request),
                        unitId(request), pass, reason));
    }

    @PostMapping("/unit/faults/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelFault(@org.springframework.web.bind.annotation.PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request,
                                                        @org.springframework.web.bind.annotation.RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return withIdempotency(idemKey, body, request, () ->
                portalService.cancelFault(id, com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), unitId(request), reason));
    }


    /** 本单位消息列表（签字提醒等，倒序 ≤50） */
    @GetMapping("/unit/messages")
    public ApiResponse<List<Map<String, Object>>> messages(HttpServletRequest request) {
        return ApiResponse.ok(clientMessageService.list(unitId(request)));
    }

    /** 本单位未读消息数 */
    @GetMapping("/unit/messages/unread-count")
    public ApiResponse<Map<String, Object>> messageUnreadCount(HttpServletRequest request) {
        return ApiResponse.ok(clientMessageService.unreadCount(unitId(request)));
    }

    /** 标记单条已读（归属校验，越权 1403） */
    @PostMapping("/unit/messages/{id}/read")
    public ApiResponse<Map<String, Object>> messageRead(
            @org.springframework.web.bind.annotation.PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(clientMessageService.markRead(unitId(request), id));
    }

    /** 全部标读（幂等，天然可重放） */
    @PostMapping("/unit/messages/read-all")
    public ApiResponse<Map<String, Object>> messageReadAll(HttpServletRequest request) {
        return ApiResponse.ok(clientMessageService.markAllRead(unitId(request)));
    }

    /** 使用单位报修（从本单位电梯选，派给该梯责任人）；报修人自动取当前登录客户账号。 */
    @PostMapping("/unit/faults")
    public ApiResponse<Map<String, Object>> reportFault(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        String unitId = unitId(request);
        Map<String, Object> payload = body == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(body);
        String reporterName = "";
        String reporterPhone = "";
        Object accountId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        if (clientAccountMapper != null && accountId != null) {
            com.cqwlw.maintenance.entity.ClientAccount account =
                    clientAccountMapper.selectById(String.valueOf(accountId));
            if (account != null) {
                reporterName = account.name == null ? "" : account.name;
                reporterPhone = account.phone == null ? "" : account.phone;
            }
        }
        // 旧统一令牌：客户主体可能仍落在员工表（ATTR_EMP_ID=员工ID），退回员工档案取联系方式
        if (reporterName.isBlank() && employeeMapper != null && accountId != null) {
            com.cqwlw.maintenance.entity.Employee employee =
                    employeeMapper.selectById(String.valueOf(accountId));
            if (employee != null) {
                reporterName = employee.name == null ? "" : employee.name;
                reporterPhone = employee.phone == null ? "" : employee.phone;
            }
        }
        // ut=1 旧门户令牌没有登录账号：退回使用单位档案的安全管理员/负责人
        if (reporterName.isBlank() && useUnitMapper != null) {
            com.cqwlw.maintenance.entity.UseUnit unit = useUnitMapper.selectById(unitId);
            if (unit != null) {
                if (unit.elevatorAdminister != null && !unit.elevatorAdminister.isBlank()) {
                    reporterName = unit.elevatorAdminister;
                    reporterPhone = unit.elevatorAdministerPhone == null ? "" : unit.elevatorAdministerPhone;
                } else if (unit.unitPrincipal != null && !unit.unitPrincipal.isBlank()) {
                    reporterName = unit.unitPrincipal;
                    reporterPhone = unit.unitPrincipalPhone == null ? "" : unit.unitPrincipalPhone;
                }
            }
        }
        if (!reporterName.isBlank()) {
            payload.put("reporter", reporterName);
        }
        if (!reporterPhone.isBlank()) {
            payload.put("reporterPhone", reporterPhone);
        }
        return ApiResponse.ok(portalService.reportFault(unitId, payload));
    }

    private ApiResponse<Map<String, Object>> withIdempotency(String key, Object body, HttpServletRequest request,
                                                               java.util.function.Supplier<Map<String, Object>> action) {
        if (idempotencyService == null) {
            return ApiResponse.ok(action.get());
        }
        return ApiResponse.ok(idempotencyService.executeDb(key, body, request, action));
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
