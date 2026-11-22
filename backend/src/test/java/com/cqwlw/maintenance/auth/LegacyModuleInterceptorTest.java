package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModuleInterceptorTest {

    private static LegacyModuleInterceptor interceptor(boolean enabled) {
        AppProperties props = new AppProperties();
        props.setLegacyModulesEnabled(enabled);
        return new LegacyModuleInterceptor(props);
    }

    @Test
    void disabledByDefaultRejectsAllNineRoutes() {
        LegacyModuleInterceptor it = interceptor(false);
        for (String path : new String[]{"/rescues", "/rescues/rs_1", "/drills", "/inspects",
                "/inspects/template", "/knowledge"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            BizException ex = assertThrows(BizException.class,
                    () -> it.preHandle(req, new MockHttpServletResponse(), new Object()), path);
            assertEquals(403, ex.getCode(), path);
        }
    }

    @Test
    void enabledPassesThrough() {
        LegacyModuleInterceptor it = interceptor(true);
        assertTrue(it.preHandle(new MockHttpServletRequest("POST", "/rescues"),
                new MockHttpServletResponse(), new Object()));
    }
}
