package com.cqwlw.maintenance.security;

import com.cqwlw.maintenance.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthContext {
    public String currentPhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String phone)) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        return phone;
    }
}
