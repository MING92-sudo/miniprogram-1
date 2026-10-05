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
    private final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;

    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this.jwtService = jwtService;
        this.employeeMapper = employeeMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Claims claims = jwtService.verify(auth.substring(7).trim());
        String client = claims.get("client", String.class);
        String sid = claims.get("sid", String.class);
        com.cqwlw.maintenance.entity.Employee user = employeeMapper.selectById(claims.getSubject());
        if (user == null) {
            // M1：先判空再取字段——员工被删除/停用时返回 401（前端清登录态），而非 500
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String current = "admin".equals(client) ? user.sessionAdmin : user.sessionId;
        if (sid == null || !sid.equals(current)) {
            throw new BizException(401, "账号已在其他设备登录，请重新登录");
        }
        request.setAttribute(ATTR_EMP_ID, claims.getSubject());
        // M2：角色以数据库为准——DB 降权/停用后旧 token 里的 role 不再生效
        request.setAttribute(ATTR_ROLE, user.role);
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        return true;
    }
}
