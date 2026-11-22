package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LegacyModuleInterceptor implements HandlerInterceptor {

    private final AppProperties appProperties;

    public LegacyModuleInterceptor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!appProperties.isLegacyModulesEnabled()) {
            throw new BizException(403, "该模块已裁剪停用（docs/01 §10.2），恢复需开启 APP_LEGACY_MODULES_ENABLED");
        }
        return true;
    }
}
