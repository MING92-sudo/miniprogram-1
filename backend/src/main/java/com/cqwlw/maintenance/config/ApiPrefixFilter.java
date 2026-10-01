package com.cqwlw.maintenance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 生产同域反代兼容：管理端 SPA 以 `/api` 前缀调用（开发期 Vite 代理 strip /api），
 * 小程序直连后端走根路径（无前缀）。云端路径路由若不 strip 前缀，则后端在此统一把 `/api/**` 改写为 `/**`，
 * 使小程序与管理端可共享同一域名；本地直连根路径不受影响。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiPrefixFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/api".equals(uri) || uri.startsWith("/api/")) {
            String stripped = uri.substring("/api".length());
            if (stripped.isEmpty()) {
                stripped = "/";
            }
            chain.doFilter(new StrippedRequest(request, stripped), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static final class StrippedRequest extends HttpServletRequestWrapper {
        private final String path;

        StrippedRequest(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            return path;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
