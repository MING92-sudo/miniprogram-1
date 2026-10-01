package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前登录人读取入口：身份由 {@link AuthInterceptor} 校验 JWT 后写入 request 属性，
 * 这里只负责读。抽成组件是为了让业务服务不必各自依赖 Servlet API。
 */
@Component
public class CurrentUser {

    public String employeeIdOrNull() {
        return (String) attr(AuthInterceptor.ATTR_EMP_ID);
    }

    public String roleOrNull() {
        return (String) attr(AuthInterceptor.ATTR_ROLE);
    }

    /**
     * 取当前登录人 id；缺失按 401 处理。这些接口都在 AuthInterceptor 之后执行，
     * 属性缺失说明鉴权链路异常，放行等于无鉴权访问（fail-closed）。
     */
    public String requireEmployeeId() {
        String id = employeeIdOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return id;
    }

    private Object attr(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }
}