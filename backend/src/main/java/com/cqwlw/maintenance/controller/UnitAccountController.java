package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.UnitAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 使用单位门户账号（W3，Task 9）：
 * - POST /unit/accounts：管理端开通（内部鉴权，角色校验在服务层）；
 * - POST /unit/login、/unit/bind-wechat：开放接口（WebConfig 已排除拦截，见 AuthInterceptor 注释）。
 * 账号挂在 use_unit 上，不建在 sys_employee（docs/14 §3）。
 */
@RestController
public class UnitAccountController {

    private final UnitAccountService accountService;

    public UnitAccountController(UnitAccountService accountService) {
        this.accountService = accountService;
    }

    /** 管理端开通/重置：返回一次性初始口令与邀请码 */
    @PostMapping("/unit/accounts")
    public ApiResponse<Map<String, Object>> createAccount(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        return ApiResponse.ok(accountService.createAccount(role, str(body, "useUnitId"),
                str(body, "account"), str(body, "password")));
    }

    /** 账号口令登录 → 门户令牌 */
    @PostMapping("/unit/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(accountService.login(str(body, "account"), str(body, "password")));
    }

    /** 邀请码绑定微信（openid 由服务端用 wx.login code 换取） */
    @PostMapping("/unit/bind-wechat")
    public ApiResponse<Map<String, Object>> bindWechat(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(accountService.bindWechat(str(body, "inviteCode"), str(body, "code")));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
