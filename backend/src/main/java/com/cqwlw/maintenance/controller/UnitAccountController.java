package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.service.UnitAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 使用单位门户账号（W3，Task 9）——V41 单账号单会话后整链废弃保留至生产清库（docs/12 G 组，2026-11-16 审查标注）：
 * - POST /unit/accounts：旧开通链（管理端现行开通走 /users UNIT 角色 + useUnitId 锚点）；
 * - POST /unit/login、/unit/login-by-wechat、/unit/bind-wechat、/unit/account/password：旧门户令牌链，
 *   前端已无调用者；已迁移账号（account_migrated=1）登录一律 422 拒绝（裁决 R4 口径）。
 * 勿据此开发新功能；清库后与 JwtService.issueUnit/AuthInterceptor 门户分支一并删除。
 */
@RestController
public class UnitAccountController {

    private final UnitAccountService accountService;
    /** 客户主体改密走 client_account（setter 注入，保持既有构造器签名） */
    private com.cqwlw.maintenance.service.ClientAccountService clientAccountService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountService(com.cqwlw.maintenance.service.ClientAccountService clientAccountService) {
        this.clientAccountService = clientAccountService;
    }

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

    /** 微信免密登录（已绑定邀请码的使用单位）：{code}（wx.login）→ 门户令牌 */
    @PostMapping("/unit/login-by-wechat")
    public ApiResponse<Map<String, Object>> loginByWechat(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(accountService.loginByWechat(str(body, "code")));
    }

    /** 邀请码绑定微信（openid 由服务端用 wx.login code 换取） */
    @PostMapping("/unit/bind-wechat")
    public ApiResponse<Map<String, Object>> bindWechat(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(accountService.bindWechat(str(body, "inviteCode"), str(body, "code")));
    }

    /** 自助改口令（门户令牌）：旧口令 + 新口令，改完旧令牌不失效、下次登录用新口令 */
    @PostMapping("/unit/account/password")
    public ApiResponse<Map<String, Object>> changePassword(@RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        // 客户主体（client_account）：口令在 client_account.password_hash，
        // 不能走 use_unit.password_hash（V41 迁移后该列已清空，会返回 401 并把客户踢回登录页）。
        if (AuthInterceptor.isCustomerRequest(request)) {
            if (clientAccountService == null) {
                throw new BizException(500, "客户账号服务未就绪");
            }
            Object accountId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
            if (accountId == null) {
                throw new BizException(401, "登录已过期，请重新登录");
            }
            clientAccountService.changePassword(String.valueOf(accountId),
                    str(body, "oldPassword"), str(body, "newPassword"));
            return ApiResponse.ok(Map.of("ok", true));
        }
        Object unitId = request.getAttribute(AuthInterceptor.ATTR_UNIT_ID);
        if (unitId == null) {
            throw new BizException(403, "请使用使用单位账号登录门户");
        }
        return ApiResponse.ok(accountService.changePassword(String.valueOf(unitId),
                str(body, "oldPassword"), str(body, "newPassword")));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
