package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuditInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.LegacyModuleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                        "/health",
                "/auth/login", "/auth/wx-login", "/auth/logout",
                "/auth/reset-password-by-wechat",
                // select-unit 不在此列——必须经 AuthInterceptor 校验短时 sel 令牌（sel=1 仅可用于该端点）

                        // bind-* 是登录后写操作，统一走 AuthInterceptor（校验 JWT + 单端会话 sid），AuthController 内仍保留 currentUser 防御，避免未来路由配置回退时越权
                        "/unit/records/*/sign-view", "/unit/records/*/confirm-by-token",
                        "/unit/records/*/refuse-by-token",
                        // 免登录 token 链签名图上传：service 内 shareToken + requireSignable 校验先于写盘
                        "/unit/records/*/signature-upload",
                        "/unit/login", "/unit/bind-wechat", "/unit/login-by-wechat",
                        // 文件写侧（upload/sts）必须登录；
                        "/", "/index.html", "/assets/**", "/favicon.ico"
                );
        // 顺序在鉴权之后：依赖 AuthInterceptor 写入的 role attribute
        registry.addInterceptor(adminRoleInterceptor)
                .addPathPatterns(
                        "/admin/**", "/reg/**", "/platform/**",
                        "/company/**", "/use-units/**", "/employees/**", "/elevators/**");
        // 审计：管理端写操作留痕（afterCompletion 按方法过滤 GET）
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns(
                        "/admin/**", "/reg/**", "/platform/**",
                        "/company/**", "/use-units/**", "/employees/**", "/elevators/**");
        registry.addInterceptor(legacyModuleInterceptor)
                .addPathPatterns(
                        "/rescues", "/rescues/**",
                        "/drills", "/drills/**",
                        "/inspects", "/inspects/**",
                        "/knowledge", "/knowledge/**");
    }
}
