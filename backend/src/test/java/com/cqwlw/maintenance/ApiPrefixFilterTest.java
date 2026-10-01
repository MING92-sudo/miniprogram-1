package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.ApiPrefixFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 生产同域反代：/api 前缀兼容（管理端 SPA 带 /api，小程序直连无前缀，二者同域共存） */
class ApiPrefixFilterTest {

    private final ApiPrefixFilter filter = new ApiPrefixFilter();

    private String strip(String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>("(not called)");
        FilterChain chain = (r, s) -> seen.set(((HttpServletRequest) r).getRequestURI());
        filter.doFilter(req, res, chain);
        return seen.get();
    }

    @Test
    void stripsApiPrefix() throws Exception {
        assertEquals("/admin/dashboard", strip("/api/admin/dashboard"));
        assertEquals("/auth/login", strip("/api/auth/login"));
    }

    @Test
    void mapsBareApiToRoot() throws Exception {
        assertEquals("/", strip("/api"));
    }

    @Test
    void keepsNonApiPaths() throws Exception {
        assertEquals("/health", strip("/health"));
        assertEquals("/auth/login", strip("/auth/login"));
    }
}
