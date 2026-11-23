package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.service.ClientAccountService;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.WxAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ClientAccountController {

    private final ClientAccountService service;
    private final IdempotencyService idempotencyService;
    private final WxAuthService wxAuthService;

    public ClientAccountController(ClientAccountService service, IdempotencyService idempotencyService,
                                   WxAuthService wxAuthService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
        this.wxAuthService = wxAuthService;
    }

    @GetMapping("/use-units/{id}/accounts")
    @RequirePerm(PermissionMatrix.USEUNIT_READ)
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable("id") String useUnitId) {
        return ApiResponse.ok(service.listByUnit(useUnitId));
    }

    @PostMapping("/use-units/{id}/accounts")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> create(@PathVariable("id") String useUnitId,
                                      @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                      HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> service.create(useUnitId, body, operatorId(request))));
    }

    @PutMapping("/use-units/{unitId}/accounts/{accountId}")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> update(@PathVariable("unitId") String unitId,
                                      @PathVariable("accountId") String accountId,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        return ApiResponse.ok(service.update(accountId, body, operatorId(request)));
    }

    @PatchMapping("/use-units/{unitId}/accounts/{accountId}/disable")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> disable(@PathVariable("unitId") String unitId,
                                       @PathVariable("accountId") String accountId,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        String reason = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        return ApiResponse.ok(service.disable(accountId, operatorId(request), reason));
    }

    @PatchMapping("/use-units/{unitId}/accounts/{accountId}/enable")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> enable(@PathVariable("unitId") String unitId,
                                      @PathVariable("accountId") String accountId,
                                      HttpServletRequest request) {
        return ApiResponse.ok(service.enable(accountId, operatorId(request)));
    }

    @PostMapping("/use-units/{unitId}/accounts/{accountId}/reset-password")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> resetPassword(@PathVariable("unitId") String unitId,
                                             @PathVariable("accountId") String accountId,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        return ApiResponse.ok(service.resetPassword(accountId, safe, operatorId(request)));
    }

    /** 换绑微信：code 经服务端 jscode2session 换真实 openid（AGENTS §2：禁止信任客户端 openid） */
    @PostMapping("/use-units/{unitId}/accounts/{accountId}/bind-wechat")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> bindWechat(@PathVariable("unitId") String unitId,
                                          @PathVariable("accountId") String accountId,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        String code = body.get("code") == null ? "" : String.valueOf(body.get("code"));
        String openid = wxAuthService.openidFromCode(code);
        return ApiResponse.ok(service.bindWechat(accountId, openid, operatorId(request)));
    }

    @PostMapping("/use-units/{unitId}/accounts/{accountId}/restore-grace")
    @RequirePerm(PermissionMatrix.USEUNIT_WRITE)
    public ApiResponse<Object> restoreGrace(@PathVariable("unitId") String unitId,
                                            @PathVariable("accountId") String accountId,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        return ApiResponse.ok(service.restoreGrace(accountId, safe, operatorId(request)));
    }

    private static String operatorId(HttpServletRequest request) {
        Object v = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        return v == null ? "" : String.valueOf(v);
    }
}
