package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 路由鉴权范围：与 mock 契约一致——除登录/绑定/分享签字/健康检查外均需 Bearer JWT；
 * 管理端路由（/admin /reg /platform /company /use-units /employees /elevators）叠加角色门禁（docs/09 §三）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminRoleInterceptor adminRoleInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AdminRoleInterceptor adminRoleInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminRoleInterceptor = adminRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/health", "/api/count",
                        "/auth/login", "/auth/wx-login", "/auth/bind-wechat",
                        "/auth/bind-employee", "/auth/bind-use-unit", "/auth/logout",
                        "/unit/records/*/sign-view", "/unit/records/*/confirm-by-token",
                        "/files/upload", "/files/*", "/location/reverse"
                );
        // 顺序在鉴权之后：依赖 AuthInterceptor 写入的 role attribute
        registry.addInterceptor(adminRoleInterceptor)
                .addPathPatterns(
                        "/admin/**", "/reg/**", "/platform/**",
                        "/company", "/use-units/**", "/employees/**", "/elevators/**");
    }
}
