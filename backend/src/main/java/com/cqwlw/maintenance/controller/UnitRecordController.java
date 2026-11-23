package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.UnitRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 使用单位确认：sign-view / confirm-by-token 凭分享 token 免登录访问。 */
@RestController
public class UnitRecordController {

    private final UnitRecordService unitRecordService;
    private final IdempotencyService idempotencyService;

    public UnitRecordController(UnitRecordService unitRecordService, IdempotencyService idempotencyService,
                                com.cqwlw.maintenance.service.RecordLifecycleService recordLifecycleService,
                                com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.unitRecordService = unitRecordService;
        this.idempotencyService = idempotencyService;
        this.recordLifecycleService = recordLifecycleService;
        this.userRoleMapper = userRoleMapper;
    }

    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;
    private final com.cqwlw.maintenance.service.RecordLifecycleService recordLifecycleService;
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    private String[] signerOf(jakarta.servlet.http.HttpServletRequest request) {
        Object principal = request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_PRINCIPAL_TYPE);
        if (principal == null || !"CUSTOMER".equals(String.valueOf(principal)) || clientAccountMapper == null) {
            return null;
        }
        Object id = request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID);
        if (id == null) {
            return null;
        }
        com.cqwlw.maintenance.entity.ClientAccount c = clientAccountMapper.selectById(String.valueOf(id));
        return c == null ? null : new String[]{c.id, c.name};
    }

    /** 旧门户令牌（ut=1）兜底 */
    private java.util.Set<String> portalRoles(jakarta.servlet.http.HttpServletRequest request) {
        java.util.Set<String> roles = com.cqwlw.maintenance.auth.MultiRoleGate.roles(request);
        if (roles.isEmpty() && request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_UNIT_ID) != null) {
            return java.util.Set.of("UNIT_ADMIN");
        }
        return roles;
    }

    private String resolveUnit(jakarta.servlet.http.HttpServletRequest request) {
        Object portal = request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_UNIT_ID);
        if (portal != null) {
            return String.valueOf(portal);
        }
        com.cqwlw.maintenance.entity.SysUserRole row = userRoleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId,
                                String.valueOf(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID)))
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE")
                        .likeRight(com.cqwlw.maintenance.entity.SysUserRole::getRoleCode, "CLIENT")
                        .last("LIMIT 1"));
        return row == null ? null : row.orgId;
    }

    @GetMapping("/unit/records/pending")
    public ApiResponse<Map<String, Object>> pending(@RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size);
        return ApiResponse.ok(unitRecordService.pendingAs(query, portalRoles(request), resolveUnit(request)));
    }

    @GetMapping("/unit/records/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id,
                                                jakarta.servlet.http.HttpServletRequest request) {
        return ApiResponse.ok(unitRecordService.getAs(id, portalRoles(request), resolveUnit(request)));
    }

    @PostMapping("/unit/records/{id}/confirm")
    public ApiResponse<Object> confirm(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                       @org.springframework.web.bind.annotation.RequestHeader(
                                               value = "X-Idempotency-Key", required = false) String idemKey,
                                       jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, safeBody, request, () -> {
            String[] signer = signerOf(request);
            return unitRecordService.confirmAs(id, safeBody, portalRoles(request), resolveUnit(request),
                    signer == null ? null : signer[0], signer == null ? null : signer[1]);
        }));
    }

    /** 使用单位拒签（凭分享 token，原因必填，退回维保人员） */
    @PostMapping("/unit/records/{id}/refuse-by-token")
    public ApiResponse<Object> refuseByToken(@PathVariable String id,
                                             @RequestParam(required = false) String token,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             @org.springframework.web.bind.annotation.RequestHeader(
                                                     value = "X-Idempotency-Key", required = false) String idemKey,
                                             jakarta.servlet.http.HttpServletRequest request) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        String code = body == null || body.get("refuseCode") == null ? "" : String.valueOf(body.get("refuseCode"));
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, safeBody, request,
                () -> recordLifecycleService.refuseByToken(id, token, code, reason)));
    }

    @PostMapping("/unit/records/batch-confirm")
    public ApiResponse<Object> batchConfirm(@RequestBody(required = false) Map<String, Object> body,
                                            @org.springframework.web.bind.annotation.RequestHeader(
                                                    value = "X-Idempotency-Key", required = false) String idemKey,
                                            jakarta.servlet.http.HttpServletRequest request) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        Object raw = safeBody.get("recordIds");
        if (raw instanceof java.util.List<?> list) {
            for (Object v : list) {
                if (v != null) {
                    ids.add(String.valueOf(v));
                }
            }
        }
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, safeBody, request, () -> {
            String[] signer = signerOf(request);
            return unitRecordService.batchConfirmAs(ids, safeBody, portalRoles(request), resolveUnit(request),
                    signer == null ? null : signer[0], signer == null ? null : signer[1]);
        }));
    }

    @GetMapping("/unit/records/{id}/sign-view")
    public ApiResponse<Map<String, Object>> signView(@PathVariable String id,
                                                     @RequestParam(required = false) String token) {
        return ApiResponse.ok(unitRecordService.signView(id, token));
    }

    @PostMapping("/unit/records/{id}/confirm-by-token")
    public ApiResponse<Object> confirmByToken(@PathVariable String id,
                                              @RequestParam(required = false) String token,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @org.springframework.web.bind.annotation.RequestHeader(
                                                      value = "X-Idempotency-Key", required = false) String idemKey,
                                              jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, safeBody, request,
                () -> unitRecordService.confirmByToken(id, token, safeBody)));
    }

    @PostMapping("/unit/records/{id}/signature-upload")
    public ApiResponse<Map<String, Object>> signatureUpload(@PathVariable String id,
                                                            @RequestParam(required = false) String token,
                                                            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                            jakarta.servlet.http.HttpServletRequest request) throws Exception {
        String schemeHost = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        return ApiResponse.ok(unitRecordService.signatureUploadByToken(id, token, file, schemeHost));
    }
}
