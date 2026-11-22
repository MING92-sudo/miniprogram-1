package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_EMP_ID = "empId";
    public static final String ATTR_ROLE = "role";
    public static final String ATTR_UNIT_ID = "useUnitId";
    /** 使用单位令牌标记（JWT claim ut=1），与员工令牌分流 */
    public static final String CLAIM_UNIT_TOKEN = "ut";
    public static final String ATTR_PRINCIPAL_TYPE = "principalType";
    public static final String ATTR_OPENID = "openid";
    public static final String ATTR_ROLES = "roles";

    public static boolean isCustomerRequest(jakarta.servlet.http.HttpServletRequest request) {
        return RoleCatalog.PrincipalType.CUSTOMER.name()
                .equals(String.valueOf(request.getAttribute(ATTR_PRINCIPAL_TYPE)));
    }

    private final JwtService jwtService;
    private final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper; // 可为 null（2 参兼容）
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper,
                           com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.jwtService = jwtService;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    /** 2 参兼容构造器（AuthInterceptorTest/UnitTokenScopeTest 锁定）：无 sys_user_role 查询，回退主角色。 */
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this(jwtService, employeeMapper, null);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 写路径（上传/直传凭证）不在此列。
        if ("GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/files/")) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Claims claims = jwtService.verify(auth.substring(7).trim());
        if ("1".equals(claims.get(CLAIM_UNIT_TOKEN, String.class))) {
            if (!"UNIT_ADMIN".equals(claims.get("role", String.class))) {
                throw new BizException(401, "登录已过期，请重新登录");
            }
            // 门户令牌只能打门户与文件接口：/handovers、/work-orders 等员工端点一律拒绝，避免使用单位账号借一个令牌读到全公司单据。
            String portalPath = request.getRequestURI();
            if (!(portalPath.startsWith("/unit/") || portalPath.startsWith("/files/"))) {
                throw new BizException(403, "使用单位账号仅可访问门户接口");
            }
            request.setAttribute(ATTR_UNIT_ID, claims.getSubject());
            request.setAttribute(ATTR_ROLE, "UNIT_ADMIN");
            request.setAttribute(ATTR_ROLES, java.util.Set.of("CLIENT_ADMIN", "UNIT_ADMIN"));
            return true;
        }
        if ("2".equals(claims.get(CLAIM_UNIT_TOKEN, String.class))) {
            return handleClientAccount(claims, request);
        }
        // 多单位选单位短时令牌（sel=1）：仅可用于 /auth/client/select-unit，其余一律 403。
        if ("1".equals(claims.get("sel", String.class))) {
            if (!"/auth/client/select-unit".equals(request.getRequestURI())) {
                throw new BizException(403, "选单位令牌仅可用于选择单位");
            }
            return true;
        }
        String client = claims.get("client", String.class);
        String sid = claims.get("sid", String.class);
        com.cqwlw.maintenance.entity.Employee user = employeeMapper.selectById(claims.getSubject());
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String current = "admin".equals(client) ? user.sessionAdmin : user.sessionId;
        if (sid == null || !sid.equals(current)) {
            throw new BizException(401, "账号已在其他设备登录，请重新登录");
        }
        // scopeVersion 校验（spec §4.2）：旧 token sv=null→0，DB null→0，不误拒
        int tokenSv = claims.get("sv") == null ? 0 : ((Number) claims.get("sv")).intValue();
        int dbSv = user.scopeVersion == null ? 0 : user.scopeVersion;
        if (tokenSv != dbSv) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        request.setAttribute(ATTR_EMP_ID, claims.getSubject());
        request.setAttribute(ATTR_PRINCIPAL_TYPE, RoleCatalog.PrincipalType.EMPLOYEE.name());
        // 角色以数据库为准——DB 降权/停用后旧 token 里的 role 不再生效
        request.setAttribute(ATTR_ROLE, user.role);
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        java.util.Set<String> roles = resolveRoles(user);
        request.setAttribute(ATTR_ROLES, roles);
        if (isCustomer(roles) && !clientAllowed(request.getRequestURI(), request.getMethod())) {
            throw new BizException(403, "客户账号仅可访问客户门户接口");
        }
        return true;
    }

    private static boolean isCustomer(java.util.Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        for (String role : roles) {
            if (RoleCatalog.principalTypeOf(role) != RoleCatalog.PrincipalType.CUSTOMER) {
                return false;
            }
        }
        return true;
    }

    /** 精确端点必须与真实端点一一对应。 */
    private static boolean clientAllowed(String uri, String method) {
        if (uri.startsWith("/client/")) {
            return true;
        }
        if (uri.startsWith("/unit/")) {
            return true;
        }
        if (uri.equals("/me/menus") || uri.equals("/auth/me") || uri.equals("/auth/change-password")
                || uri.equals("/auth/bind-wechat")
                || uri.equals("/auth/client/me")
                || uri.equals("/auth/client/change-password")
                || uri.equals("/auth/logout") || uri.equals("/health")) {
            return true;
        }
        // 客户报修现场照片上传：仅 POST /files/upload 精确放行，FileController.upload 不依赖员工目录；
        if ("POST".equalsIgnoreCase(method) && uri.equals("/files/upload")) {
            return true;
        }
        return "GET".equalsIgnoreCase(method)
                && (uri.startsWith("/files/")
                    || uri.equals("/elevators")
                    || uri.equals("/elevators/by-code")
                    || uri.matches("/elevators/[^/]+/profile"));
    }

    /** 客户令牌绝不进入员工目录查询。 */
    private boolean handleClientAccount(io.jsonwebtoken.Claims claims, HttpServletRequest request) {
        String accountId = claims.getSubject();
        if (clientAccountMapper == null) {
            throw new BizException(401, "客户鉴权未就绪，请重新登录");
        }
        com.cqwlw.maintenance.entity.ClientAccount c = clientAccountMapper.selectById(accountId);
        if (c == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        // 状态门禁：DISABLED 不可登录；GRACE 可登录但按业务收敛（服务层按 status 判定能力）
        if (com.cqwlw.maintenance.entity.ClientAccount.STATUS_DISABLED.equals(c.status)
                || (c.enabled != null && c.enabled == 0
                    && !com.cqwlw.maintenance.entity.ClientAccount.STATUS_GRACE.equals(c.status))) {
            throw new BizException(401, "账号已停用，请联系使用单位管理员");
        }
        String sid = claims.get("sid", String.class);
        if (sid == null || !sid.equals(c.sessionId)) {
            throw new BizException(401, "账号已在其他设备登录，请重新登录");
        }
        int tokenSv = claims.get("sv") == null ? 0 : ((Number) claims.get("sv")).intValue();
        int dbSv = c.scopeVersion == null ? 0 : c.scopeVersion;
        if (tokenSv != dbSv) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String role = c.roleCode;
        String uri = request.getRequestURI();
        if (!clientAllowed(uri, request.getMethod())) {
            throw new BizException(403, "客户账号仅可访问客户门户接口");
        }
        request.setAttribute(ATTR_EMP_ID, accountId);
        request.setAttribute(ATTR_ROLE, role);
        request.setAttribute(ATTR_ROLES, java.util.Set.of(role));
        request.setAttribute(ATTR_UNIT_ID, c.useUnitId);
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        request.setAttribute(ATTR_PRINCIPAL_TYPE, RoleCatalog.PrincipalType.CUSTOMER.name());
        return true;
    }

    private java.util.Set<String> resolveRoles(com.cqwlw.maintenance.entity.Employee user) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        if (userRoleMapper != null) {
            for (com.cqwlw.maintenance.entity.SysUserRole ur : userRoleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                            .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId, user.id)
                            .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE"))) {
                String c = RoleCatalog.canonical(ur.roleCode);
                if (c != null) {
                    roles.add(c);
                }
            }
        }
        String c = RoleCatalog.canonical(user.role);
        if (c != null) {
            roles.add(c);
        }
        return roles;
    }
}
