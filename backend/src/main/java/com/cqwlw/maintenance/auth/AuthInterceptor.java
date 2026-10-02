package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器：校验 Authorization: Bearer <JWT>（utils/request.js 约定）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_EMP_ID = "empId";
    public static final String ATTR_ROLE = "role";
    public static final String ATTR_OPENID = "openid";

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 读取本系统文件走 URL 访问签名把门，不走 JWT：小程序 <image src> 不会带 Authorization 头，
        // 且使用单位签字页是经 shareToken 匿名访问、同样要渲染签名图。写路径（上传/直传凭证）不在此列。
        if ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/files/")) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Claims claims = jwtService.verify(auth.substring(7).trim());
        request.setAttribute(ATTR_EMP_ID, claims.getSubject());
        request.setAttribute(ATTR_ROLE, claims.get("role"));
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        return true;
    }
}
