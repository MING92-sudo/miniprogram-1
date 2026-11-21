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

/**
 * 使用单位确认（docs/04 A.3）：sign-view / confirm-by-token 凭分享 token 免登录访问。
 */
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
    /** P3 客户账号 Mapper（setter 注入，客户令牌签字时取真实账号身份） */
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    /** P3：客户令牌（ut=2）签字身份——从令牌 subject 查库取真实账号 id + 姓名；非客户令牌返回 null。 */
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

    /** M1：门户读端点改统一令牌 + CLIENT 谓词；旧门户令牌（ut=1）兜底 */
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
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/confirm");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        String[] signer = signerOf(request);
        Map<String, Object> result = unitRecordService.confirmAs(id, body == null ? Map.of() : body,
                portalRoles(request), resolveUnit(request),
                signer == null ? null : signer[0], signer == null ? null : signer[1]);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    /** M1：使用单位拒签（凭分享 token，原因必填，退回维保人员） */
    @PostMapping("/unit/records/{id}/refuse-by-token")
    public ApiResponse<Object> refuseByToken(@PathVariable String id,
                                             @RequestParam(required = false) String token,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             @org.springframework.web.bind.annotation.RequestHeader(
                                                     value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/refuse-by-token");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        // V56：拒签原因分类码（R1—R5；旧客户端未传码时按兼容期「原因必填」回退）
        String code = body == null || body.get("refuseCode") == null ? "" : String.valueOf(body.get("refuseCode"));
        Map<String, Object> result = recordLifecycleService.refuseByToken(id, token, code, reason);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    /** V56 批量签字（docs/04 V2.80）：同一签名一次确认多条待签记录（≤20），逐条独立门禁、失败不拖累其余 */
    @PostMapping("/unit/records/batch-confirm")
    public ApiResponse<Object> batchConfirm(@RequestBody(required = false) Map<String, Object> body,
                                            @org.springframework.web.bind.annotation.RequestHeader(
                                                    value = "X-Idempotency-Key", required = false) String idemKey,
                                            jakarta.servlet.http.HttpServletRequest request) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/batch-confirm");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        Object raw = body == null ? null : body.get("recordIds");
        if (raw instanceof java.util.List<?> list) {
            for (Object v : list) {
                if (v != null) {
                    ids.add(String.valueOf(v));
                }
            }
        }
        String[] signer = signerOf(request);
        Map<String, Object> result = unitRecordService.batchConfirmAs(ids, body == null ? Map.of() : body,
                portalRoles(request), resolveUnit(request),
                signer == null ? null : signer[0], signer == null ? null : signer[1]);
        guard.commit(result);
        return ApiResponse.ok(result);
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
                                                      value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/confirm-by-token");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = unitRecordService.confirmByToken(id, token, body == null ? Map.of() : body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    /** V2.80 修复：免登录 token 链签名图上传（/files/upload 必须登录，匿名签字页此前无上传通道） */
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
