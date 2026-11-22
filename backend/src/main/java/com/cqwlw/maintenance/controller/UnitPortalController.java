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
    private IdempotencyService idempotencyService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdempotencyService(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    /** V56 客户门户消息（setter 注入，保持既有构造器签名） */
    private com.cqwlw.maintenance.service.ClientMessageService clientMessageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientMessageService(com.cqwlw.maintenance.service.ClientMessageService clientMessageService) {
        this.clientMessageService = clientMessageService;
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
        return withIdempotency(idemKey, "/unit/faults/confirm", () ->
                portalService.confirmFault(id, com.cqwlw.maintenance.auth.MultiRoleGate.roles(request),
                        unitId(request), pass, reason));
    }

    @PostMapping("/unit/faults/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelFault(@org.springframework.web.bind.annotation.PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request,
                                                        @org.springframework.web.bind.annotation.RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return withIdempotency(idemKey, "/unit/faults/cancel", () ->
                portalService.cancelFault(id, com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), unitId(request), reason));
    }

    // ── 客户门户消息（V56 签字临期/超期提醒，docs/04 V2.80；数据域=本单位锚点）──

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

    /** 使用单位报修（从本单位电梯选，派给该梯责任人） */
    @PostMapping("/unit/faults")
    public ApiResponse<Map<String, Object>> reportFault(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(portalService.reportFault(unitId(request), body));
    }

    private ApiResponse<Map<String, Object>> withIdempotency(String key, String path,
                                                               java.util.function.Supplier<Map<String, Object>> action) {
        if (idempotencyService == null) {
            return ApiResponse.ok(action.get());
        }
        IdempotencyService.Guard guard = idempotencyService.begin(key, path);
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = action.get();
        guard.commit(result);
        return ApiResponse.ok(result);
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
