package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 路由鉴权范围：与 mock 契约一致——除登录/绑定/分享签字/健康检查外均需 Bearer JWT。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
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
    }
}
