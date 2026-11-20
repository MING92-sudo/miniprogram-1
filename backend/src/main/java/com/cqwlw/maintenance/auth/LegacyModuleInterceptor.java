package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 已裁合规矩阵台账模块总开关（docs/01 §10.2）：/rescues、/drills、/inspects、/knowledge 共 9 条端点。
 * 模块已裁剪（前端零调用、数据保留），默认一律 403；恢复交付时置 APP_LEGACY_MODULES_ENABLED=true。
 * 注册顺序在 AuthInterceptor 之后（先认证后门禁），覆盖路径见 WebConfig。
 */
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
