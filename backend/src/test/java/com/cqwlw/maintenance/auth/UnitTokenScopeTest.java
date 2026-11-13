package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门户令牌的作用域（docs/14 §4 部门域互斥）：使用单位账号只能打 /unit/** 与 /files/**，
 * 借门户令牌读取移交单、工单等员工端点必须 403。
 */
class UnitTokenScopeTest {

    private static AuthInterceptor interceptor(String subject, boolean unitToken) {
        JwtService jwt = mock(JwtService.class);
        Claims claims = new DefaultClaims(Map.of(
                "sub", subject,
                "role", unitToken ? "UNIT_ADMIN" : "WORKER",
                "ut", unitToken ? "1" : "0",
                "client", "mp",
                "sid", "s1"));
        when(jwt.verify("t")).thenReturn(claims);
        return new AuthInterceptor(jwt, mock(EmployeeMapper.class));
    }

    private static MockHttpServletRequest req(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.addHeader("Authorization", "Bearer t");
        return r;
    }

    /** 门户令牌读移交单列表 → 403（不能借令牌看全公司单据） */
    @Test
    void unitTokenCannotReadStaffEndpoints() {
        BizException ex = assertThrows(BizException.class,
                () -> interceptor("uu_1", true).preHandle(req("/handovers"), null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("门户"));

        BizException work = assertThrows(BizException.class,
                () -> interceptor("uu_1", true).preHandle(req("/work-orders"), null, null));
        assertEquals(403, work.getCode());
    }

    /** 门户与上传接口放行，并写入 useUnitId/role */
    @Test
    void unitTokenAllowedOnPortalAndFiles() {
        MockHttpServletRequest portal = req("/unit/elevators");
        assertTrue(interceptor("uu_1", true).preHandle(portal, null, null));
        assertEquals("uu_1", portal.getAttribute(AuthInterceptor.ATTR_UNIT_ID));
        assertEquals("UNIT_ADMIN", portal.getAttribute(AuthInterceptor.ATTR_ROLE));

        assertTrue(interceptor("uu_1", true).preHandle(req("/files/upload"), null, null));
    }
}
