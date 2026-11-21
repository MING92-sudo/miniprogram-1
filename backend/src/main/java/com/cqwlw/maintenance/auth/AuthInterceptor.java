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
    /** P3 主体类型属性：客户令牌（ut=2）写 CUSTOMER，员工令牌不写（默认 EMPLOYEE） */
    public static final String ATTR_PRINCIPAL_TYPE = "principalType";
    public static final String ATTR_OPENID = "openid";
    /** 规范角色码集（Set<String>，M1 多角色）；员工分支由 sys_user_role 填，门户分支含 CLIENT_ADMIN */
    public static final String ATTR_ROLES = "roles";

    /**
     * 当前请求是否为客户主体（client_account）。
     *
     * <p>统一登录改造后，客户令牌的 subject 是 {@code client_account.id}，查员工目录必然为空。
     * 端点若不先判主体就调 {@code currentUser()}，会对客户返回 401；前端 request 层把 401 当
     * 登录失效并清登录态，表现为「客户登录后被莫名踢回登录页」。
     * 白名单内的端点一律先过这个方法，再决定走员工分支还是客户分支（缺陷 BUG-1 的成因）。
     */
    public static boolean isCustomerRequest(jakarta.servlet.http.HttpServletRequest request) {
        return RoleCatalog.PrincipalType.CUSTOMER.name()
                .equals(String.valueOf(request.getAttribute(ATTR_PRINCIPAL_TYPE)));
    }

    private final JwtService jwtService;
    private final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper; // 可为 null（2 参兼容）
    /** P3 客户账号 Mapper（setter 注入，可为 null——2 参/3 参兼容构造器单测不覆盖客户令牌） */
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper,
                           com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.jwtService = jwtService;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** P3 客户账号 Mapper 注入（生产 Spring 调用；单测可忽略） */
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
        // P3 客户账号令牌（ut=2，docs/14 §8）：subject=client_account.id，独立于员工目录。
        // 校验：账号存在 + 状态 ACTIVE/GRACE + 单会话 sid + scope_version；任一失败 401。
        // 路由：客户白名单（/client/** + 自身账号端点 + 读接口），其余 403。
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
        request.setAttribute(ATTR_PRINCIPAL_TYPE, RoleCatalog.PrincipalType.EMPLOYEE.name());
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
     * POST /files/upload（2026-11-21 重开 client:report 配套：报修现场照片）、
     * GET /files/*（与 L49 前置放行口径对齐：常规请求在鉴权前已放行，此处为白名单面兜底；
     * 时效签名在 FileController 校验）与
     * GET /elevators*（含 profile，本单位谓词在 ElevatorController 已有，跨单位 1403）。
     * 其余一律拒绝——尤其 /users /org /stats /audit /invites /grants /projects /roles-matrix
     * /admin/ /reg/ /platform/ /employees/ /use-units/ /home/summary。
     *
     * <p>维护规则（2026-11-20 静态排查后收紧）：精确端点必须与真实存在的端点一一对应。
     * 曾登记 `/auth/client/switch-unit`、`/auth/client/bind-wechat` 两个不存在的端点，
     * 属无效放行——会让人误以为客户有对应能力。现按 fail-closed 删除；新增客户端点必须显式登记，
     * 并由 {@code scripts/e2e/scan-customer-principal.js} 复核「放行 + 主体支持」是否成对。
     */
    private static boolean clientAllowed(String uri, String method) {
        // P3 客户主体新端点族：/client/** 全部放行（内部谓词在服务层按 token useUnitId 收敛）。
        // 现状（2026-11-20 排查）：该前缀下尚无任何端点，访问会 404；保留是既定设计预留，
        // 但它属"前缀全放"，将来新增 /client/* 会自动对客户开放——若要长期不用建议整体删除。
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
        // 客户报修现场照片上传（2026-11-21 重开 client:report 配套）：仅 POST /files/upload 精确放行，
        // FileController.upload 不依赖员工目录；其余 /files 写侧仍不对客户开放
        if ("POST".equalsIgnoreCase(method) && uri.equals("/files/upload")) {
            return true;
        }
        // 读口径与 AdminRoleInterceptor 的「GET /elevators* 小程序共用读接口」一致；写操作（派单等）不放行。
        // 精确到三个客户真正需要的读端点，而不是 /elevators/** 前缀全放：
        // GET /elevators/{id}/archive 属移交档案（handover:view），客户角色无该能力，
        // 前缀全放会让客户"够得着"一个与自身无关的内部档案端点（虽被权限矩阵 403 兜住）。
        return "GET".equalsIgnoreCase(method)
                && (uri.startsWith("/files/")
                    || uri.equals("/elevators")
                    || uri.equals("/elevators/by-code")
                    || uri.matches("/elevators/[^/]+/profile"));
    }

    /**
     * P3 客户令牌（ut=2）校验（docs/14 §4/§8）：
     * 账号存在 + 状态 ACTIVE/GRACE（DISABLED 立即 401）+ 单会话 sid + scope_version + 路径白名单。
     * 与员工分支同口径 fail-closed；客户令牌绝不进入员工目录查询。
     */
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
