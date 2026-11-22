package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.auth.AdminRoleInterceptor;
import com.cqwlw.maintenance.auth.AuditInterceptor;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.LegacyModuleInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationEndpointSecurityTest {

    @Test
    void reverseGeocodeIsNotExcludedFromAuthentication() {
        AuthInterceptor auth = mock(AuthInterceptor.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any())).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);
        when(registration.excludePathPatterns(any(String[].class))).thenReturn(registration);
        WebConfig config = new WebConfig(auth, mock(AdminRoleInterceptor.class),
                mock(AuditInterceptor.class), mock(LegacyModuleInterceptor.class));

        config.addInterceptors(registry);

        ArgumentCaptor<String[]> excluded = ArgumentCaptor.forClass(String[].class);
        org.mockito.Mockito.verify(registration).excludePathPatterns(excluded.capture());
        List<String> patterns = List.of(excluded.getValue());
        assertFalse(patterns.contains("/location/reverse"), "匿名 LBS 代理会消耗后端付费配额");
    }
}
