package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuditInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.LegacyModuleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 路由鉴权范围：与 mock 契约一致——除登录/绑定/分享签字/健康检查外均需 Bearer JWT；
 * 管理端路由（/admin /reg /platform /company /use-units /employees /elevators）叠加角色门禁（docs/09 §三）；
 * 管理端写操作叠加审计留痕（docs/02 op_log）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminRoleInterceptor adminRoleInterceptor;
    private final AuditInterceptor auditInterceptor;
    private final LegacyModuleInterceptor legacyModuleInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AdminRoleInterceptor adminRoleInterceptor,
                     AuditInterceptor auditInterceptor, LegacyModuleInterceptor legacyModuleInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminRoleInterceptor = adminRoleInterceptor;
        this.auditInterceptor = auditInterceptor;
        this.legacyModuleInterceptor = legacyModuleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/health", "/api/count",
                "/auth/login", "/auth/wx-login", "/auth/logout",
                "/auth/reset-password-by-wechat",
                // V2.68 统一登录：客户账号并入 /auth/login 与 /auth/wx-login（自动分流）；
                // select-unit 不在此列——必须经 AuthInterceptor 校验短时 sel 令牌（sel=1 仅可用于该端点）

                        // bind-* 是登录后写操作，统一走 AuthInterceptor（校验 JWT + 单端会话 sid），
                        // AuthController 内仍保留 currentUser 防御，避免未来路由配置回退时越权
                        "/unit/records/*/sign-view", "/unit/records/*/confirm-by-token",
                        "/unit/records/*/refuse-by-token",
                        // 免登录 token 链签名图上传：service 内 shareToken + requireSignable 校验先于写盘
                        "/unit/records/*/signature-upload",
                        // 使用单位门户开放接口（Task 9）：登录与邀请码绑定本身无令牌；/unit/accounts 仍需登录+角色校验
                        "/unit/login", "/unit/bind-wechat", "/unit/login-by-wechat",
                        // 文件写侧（upload/sts）必须登录；GET /files/{id} 由 FileAccessTokenService 的
                        // URL 时效签名把门（AuthInterceptor 对 GET 放行，Controller 校验 s=）
                        "/location/reverse",
                        "/", "/index.html", "/assets/**", "/favicon.ico"
                );
        // 顺序在鉴权之后：依赖 AuthInterceptor 写入的 role attribute
        registry.addInterceptor(adminRoleInterceptor)
                .addPathPatterns(
                        "/admin/**", "/reg/**", "/platform/**",
                        "/company", "/use-units/**", "/employees/**", "/elevators/**");
        // 审计：管理端写操作留痕（afterCompletion 按方法过滤 GET）
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns(
                        "/admin/**", "/reg/**", "/platform/**",
                        "/company", "/use-units/**", "/employees/**", "/elevators/**");
        // 已裁合规矩阵台账模块总开关（docs/01 §10.2）：默认 403，APP_LEGACY_MODULES_ENABLED=true 恢复
        registry.addInterceptor(legacyModuleInterceptor)
                .addPathPatterns(
                        "/rescues", "/rescues/**",
                        "/drills", "/drills/**",
                        "/inspects", "/inspects/**",
                        "/knowledge", "/knowledge/**");
    }
}
