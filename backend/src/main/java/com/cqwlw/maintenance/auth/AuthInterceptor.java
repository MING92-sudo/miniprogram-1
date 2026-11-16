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
    /** 使用单位门户令牌的 use_unit.id（Task 9）；员工令牌下为 null */
    public static final String ATTR_UNIT_ID = "useUnitId";
    /** 使用单位令牌标记（JWT claim ut=1），与员工令牌分流 */
    public static final String CLAIM_UNIT_TOKEN = "ut";
    public static final String ATTR_OPENID = "openid";
    /** 规范角色码集（Set<String>，M1 多角色）；员工分支由 sys_user_role 填，门户分支含 CLIENT_ADMIN */
    public static final String ATTR_ROLES = "roles";

    private final JwtService jwtService;
    private final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper; // 可为 null（2 参兼容）

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper,
                           com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.jwtService = jwtService;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** 2 参兼容构造器（AuthInterceptorTest/UnitTokenScopeTest 锁定）：无 sys_user_role 查询，回退主角色。 */
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this(jwtService, employeeMapper, null);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 读取本系统文件走 URL 访问签名把门，不走 JWT：小程序 <image src> 不会带 Authorization 头，
        // 且使用单位签字页是经 shareToken 匿名访问、同样要渲染签名图。写路径（上传/直传凭证）不在此列。
        if ("GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/files/")) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Claims claims = jwtService.verify(auth.substring(7).trim());
        // 使用单位门户令牌（Task 9）：subject 是 use_unit.id，不查员工目录、不参与单端会话覆盖
        if ("1".equals(claims.get(CLAIM_UNIT_TOKEN, String.class))) {
            if (!"UNIT_ADMIN".equals(claims.get("role", String.class))) {
                throw new BizException(401, "登录已过期，请重新登录");
            }
            // 门户令牌只能打门户与文件接口：/handovers、/filings、/work-orders 等员工端点一律拒绝，
            // 避免使用单位账号借一个令牌读到全公司单据（docs/14 §4 部门域互斥）。
            String portalPath = request.getRequestURI();
            if (!(portalPath.startsWith("/unit/") || portalPath.startsWith("/files/"))) {
                throw new BizException(403, "使用单位账号仅可访问门户接口");
            }
            request.setAttribute(ATTR_UNIT_ID, claims.getSubject());
            request.setAttribute(ATTR_ROLE, "UNIT_ADMIN");
            request.setAttribute(ATTR_ROLES, java.util.Set.of("CLIENT_ADMIN", "UNIT_ADMIN"));
            return true;
        }
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
        // scopeVersion 校验（spec §4.2）：旧 token sv=null→0，DB null→0，不误拒
        int tokenSv = claims.get("sv") == null ? 0 : ((Number) claims.get("sv")).intValue();
        int dbSv = user.scopeVersion == null ? 0 : user.scopeVersion;
        if (tokenSv != dbSv) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        request.setAttribute(ATTR_EMP_ID, claims.getSubject());
        // M2：角色以数据库为准——DB 降权/停用后旧 token 里的 role 不再生效
        request.setAttribute(ATTR_ROLE, user.role);
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        java.util.Set<String> roles = resolveRoles(user);
        request.setAttribute(ATTR_ROLES, roles);
        // P1 T3：客户主体（使用单位账号）路由门禁——员工统一令牌此前无按角色路径限制，
        // 客户令牌可直达 /users /org /stats /audit 等内部/管理端点（AdminRoleInterceptor 覆盖不到 M3/M5 路由）。
        // 混合角色（含任一内部角色）走员工路径不误锁；403 与 ut=1 门户分支同写法，
        // 经 GlobalExceptionHandler 统一 {code,message} 响应并落 BLOCKED 审计（spec §5.2）。
        if (isCustomer(roles) && !clientAllowed(request.getRequestURI(), request.getMethod())) {
            throw new BizException(403, "客户账号仅可访问客户门户接口");
        }
        return true;
    }

    /** 客户主体判定（P1）：roles 非空且全部规范码为 CUSTOMER；含任一内部角色（或空集）不视为客户。 */
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

    /**
     * 客户主体业务路由白名单（P1 兼容 /unit/**，不新增 /client/** 端点）：
     * 门户前缀、自身账号端点（菜单/本人信息/改密/绑微信/登出）、健康检查、
     * GET /files/*（鉴权前已放行，此处兜底；时效签名在 FileController 校验）与
     * GET /elevators*（含 profile，本单位谓词在 ElevatorController 已有，跨单位 1403）。
     * 其余一律拒绝——尤其 /users /org /stats /audit /invites /grants /projects /roles-matrix
     * /admin/ /reg/ /platform/ /employees/ /use-units/ /home/summary。
     */
    private static boolean clientAllowed(String uri, String method) {
        if (uri.startsWith("/unit/")) {
            return true;
        }
        if (uri.equals("/me/menus") || uri.equals("/auth/me") || uri.equals("/auth/change-password")
                || uri.equals("/auth/bind-wechat") || uri.equals("/auth/logout") || uri.equals("/health")) {
            return true;
        }
        // 读口径与 AdminRoleInterceptor 的「GET /elevators* 小程序共用读接口」一致；写操作（派单等）不放行
        return "GET".equalsIgnoreCase(method) && (uri.startsWith("/files/") || uri.startsWith("/elevators"));
    }

    /**
     * 多角色规范码集（P1 终审 I2）：sys_user_role 行 ∪ 主角色 canonical——与 UserRoleService.canonicalRolesOf
     * （登录 principalType/perms 同源）同口径。历史混合形态（V41 合并产生主角色=WORKER + 行仅 CLIENT_ADMIN）
     * 不得丢内部主角色被判全客户锁进白名单。2 参兼容构造器无行查询，仅主角色。
     */
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
