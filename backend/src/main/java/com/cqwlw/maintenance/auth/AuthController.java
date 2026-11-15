package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证（docs/04 A.1 契约）：账号密码登录（BCrypt）→ 自建 JWT；
 * 微信侧一律由 wx.login 的 code 经 jscode2session 换真实 openid（bind-wechat 落库 / wx-login 查账号）。
 *
 * <p>openid 只有一个来源——后端 jscode2session（WxAuthService）：
 * 不再回退 app.dev-openid，也不再信任可伪造的 X-WX-OPENID 请求头。否则任何 HTTP 客户端
 * 自带一个 openid 就能签发该账号 JWT，或全员落库同一 dev_openid 导致按 openid 查人串号。
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;
    private final WxAuthService wxAuthService;
    private final MenuService menuService;
    /** 同一登录页的使用单位门户分支（W3）：员工账号查不到时再试门户账号 */
    private final com.cqwlw.maintenance.service.UnitAccountService unitAccountService;
    /** M1：规范角色集（sys_user_role ∪ 旧主角色别名归一） */
    private final UserRoleService userRoleService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(EmployeeMapper employeeMapper, JwtService jwtService, WxAuthService wxAuthService,
                          MenuService menuService,
                          com.cqwlw.maintenance.service.UnitAccountService unitAccountService,
                          UserRoleService userRoleService) {
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
        this.wxAuthService = wxAuthService;
        this.menuService = menuService;
        this.unitAccountService = unitAccountService;
        this.userRoleService = userRoleService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String phone = body.get("phone") == null ? "" : String.valueOf(body.get("phone")).trim();
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getAccount, phone).last("LIMIT 1"));
        if (user == null) {
            // 同一登录页：员工目录查不到时按使用单位门户账号再试（W3），前端据 portal=true 分流
            return ApiResponse.ok(unitLoginResult(phone, password, body));
        }
        if (!encoder.matches(password, user.passwordHash)) {
            throw new BizException(401, "账号或密码错误");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        // 管理端会话白名单（docs/09 §3.2）：client=admin 时仅 LEADER/ADMIN/SYS_ADMIN 可登录；
        // 小程序端（不带 client）不受影响。
        if ("admin".equals(String.valueOf(body.get("client")))) {
            if (!AdminRoles.canUseAdminConsole(user.role)) {
                throw new BizException(403, "该账号无管理端权限，请使用小程序登录");
            }
        }
        String client = "admin".equals(String.valueOf(body.get("client"))) ? "admin" : "mp";
        return ApiResponse.ok(loginResult(user, null, client));
    }

    /** 使用单位门户账号登录（同一登录页的分支）：门户令牌 + portal=true，供前端跳门户首页。
     * @deprecated M1 账号统一后仅作为 account_migrated=0 跨部门兜底（裁决 R4），M6 删除。 */
    @Deprecated
    private Map<String, Object> unitLoginResult(String account, String password, Map<String, Object> body) {
        if ("admin".equals(String.valueOf(body.get("client")))) {
            throw new BizException(403, "使用单位账号请使用小程序登录");
        }
        Map<String, Object> portal = unitAccountService.login(account, password);
        Map<String, Object> out = new java.util.LinkedHashMap<>(portal);
        out.put("portal", true);
        out.put("userInfo", Map.of("name", portal.get("unitName") == null ? "" : portal.get("unitName")));
        return out;
    }

    /**
     * 微信绑定（登录后调用，需 Bearer）。openid 由 code 换取并写入当前账号。
     * 事务：先解绑同一 openid 的其他账号、再写当前账号，两写必须同成同败
     * （否则解绑成功、绑定失败会让原账号丢掉微信登录）。
     */
    @PostMapping("/auth/bind-wechat")
    @Transactional
    public ApiResponse<Map<String, Object>> bindWeChat(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Employee current = currentUser(request);
        if (current == null) {
            throw new BizException(401, "登录已过期，请重新登录后再绑定微信");
        }
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        // 一个微信只对应一个账号：显式 SQL 清掉其他持有者（含历史重复行），再由唯一键兜底
        int released = employeeMapper.unbindOpenidExcept(openid, current.id);
        if (released > 0) {
            log.info("openid 已从 {} 个其他账号解绑", released);
        }
        current.openid = openid;
        employeeMapper.updateById(current);
        log.info("微信绑定成功: empId={}", current.id); // 不打印 openid（脱敏，AGENTS §2.4）
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/auth/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user == null) {
            // 422 而非 401：前端 request.js 对 401 会清登录态，一键登录入口若在已登录页面调用会误登出
            throw new BizException(422, "该微信尚未绑定账号，请先用账号密码登录并绑定微信");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        return ApiResponse.ok(loginResult(user, null, "mp"));
    }

    @PostMapping("/auth/bind-employee")
    public ApiResponse<Map<String, Object>> bindEmployee(@RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request) {
        String role = body != null && "LEADER".equals(body.get("role")) ? "LEADER" : "WORKER";
        return ApiResponse.ok(roleLogin(request, role, false));
    }

    @PostMapping("/auth/bind-use-unit")
    public ApiResponse<Map<String, Object>> bindUseUnit(HttpServletRequest request) {
        return ApiResponse.ok(roleLogin(request, "UNIT_ADMIN", true));
    }

    /** 用户需求④：登录用户自助改密（新密码 ≥6 位；既有 JWT 不失效，重新登录用新密码） */
    @PostMapping("/auth/change-password")
    public ApiResponse<Map<String, Object>> changePassword(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String oldPassword = body.get("oldPassword") == null ? "" : String.valueOf(body.get("oldPassword"));
        String newPassword = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        if (!encoder.matches(oldPassword, user.passwordHash)) {
            throw new BizException(422, "原密码不正确");
        }
        if (newPassword.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(422, "新密码不能与原密码相同");
        }
        user.passwordHash = encoder.encode(newPassword);
        employeeMapper.updateById(user);
        return ApiResponse.ok(Map.of("ok", true));
    }
    /** 忘记密码（登录页入口，免登录）：code 服务端换 openid 匹配绑定账号后重置；重置后清两端会话。 */
    @PostMapping("/auth/reset-password-by-wechat")
    @Transactional
    public ApiResponse<Map<String, Object>> resetPasswordByWechat(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(422, "该微信尚未绑定账号，请联系单位管理员重置密码");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        String newPassword = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        if (newPassword.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        user.passwordHash = encoder.encode(newPassword);
        employeeMapper.updateById(user);
        employeeMapper.clearSessions(user.id);
        log.info("微信验证重置密码成功: empId={}", user.id); // 不打印 openid/新密码（脱敏）
        return ApiResponse.ok(Map.of("ok", true, "account", user.account == null ? "" : user.account));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout() {
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        return ApiResponse.ok(Map.of("userInfo", userInfo(user), "role", role));
    }

    /**
     * 管理端菜单（docs/14 §9.3、§11 P2）：按登录角色能力过滤后下发，前端只渲染下发的项。
     * 不可登录管理端的角色（WORKER/INSTALL_WORKER/USE_UNIT 等）返回空列表，由前端提示走小程序。
     */
    @GetMapping("/auth/menus")
    public ApiResponse<Map<String, Object>> menus(HttpServletRequest request) {
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", role);
        body.put("menus", menuService.menusOf(role));
        return ApiResponse.ok(body);
    }

    private Map<String, Object> roleLogin(HttpServletRequest request, String requestedRole,
                                          boolean forceUnitAdmin) {
        Employee current = currentUser(request);
        if (current == null) {
            throw new BizException(401, "请先登录后再切换角色");
        }
        // 防越权：角色只能取员工在库中的真实角色，禁止采信客户端 body 指定的角色
        //（否则任意 WORKER 可自铸 LEADER/UNIT_ADMIN 令牌读取整个管理端）
        String targetRole = forceUnitAdmin ? "UNIT_ADMIN" : requestedRole;
        if (!targetRole.equals(current.role)) {
            throw new BizException(403, "无权使用该角色");
        }
        return loginResult(current, null, "mp");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> loginResult(Employee user, String openid, String client) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", issueSessionToken(user, user.role, openid, client));
        out.put("userInfo", userInfo(user));
        out.put("role", user.role);
        // M1 扩展（规格 §3.2）：多角色并集 + 能力码并集 + 范围投影 + 默认工作台；旧字段保留兼容
        java.util.Set<String> roles = userRoleService.canonicalRolesOf(user.id, user.role);
        out.put("roles", new java.util.ArrayList<>(roles));
        out.put("perms", PermissionMatrix.canonicalCapabilities().stream()
                .filter(c -> roles.stream().anyMatch(r -> PermissionMatrix.allowsNew(r, c)))
                .collect(java.util.stream.Collectors.toList()));
        out.put("scope", scopeText(user));
        out.put("activeWorkspace", MiniappMenuService.activeWorkspace(roles));
        out.put("home", MiniappMenuService.resolveHome(roles));
        return out;
    }

    /** 范围一句话投影（部门 + 岗位）；数据范围权威仍是 ScopeResolver 读写谓词 */
    private static String scopeText(Employee user) {
        String dept = user.department;
        if (dept == null || dept.isEmpty()) {
            String r = user.role == null ? "" : user.role;
            dept = r.startsWith("MAINT") || r.equals("WORKER") || r.equals("ASSISTANT") || r.equals("LEADER")
                    ? "MAINT" : r.startsWith("INST") || r.startsWith("INSTALL") ? "INSTALL"
                    : r.startsWith("CLIENT") || r.equals("UNIT_ADMIN") ? "UNIT" : "PLATFORM";
        }
        String deptName = switch (dept) {
            case "INSTALL" -> "安装部";
            case "MAINT" -> "维保部";
            case "UNIT" -> "使用单位";
            default -> "平台";
        };
        String job = user.roleText == null || user.roleText.isEmpty() ? user.role : user.roleText;
        return deptName + "·" + job;
    }

    /** 单端登录（按端隔离）：签发覆盖对应端会话，同端互踢、跨端互不干扰 */
    private String issueSessionToken(Employee user, String role, String openid, String client) {
        String sid = Ids.next("sess");
        if ("admin".equals(client)) {
            user.sessionAdmin = sid;
        } else {
            user.sessionId = sid;
        }
        employeeMapper.updateById(user);
        int sv = user.scopeVersion == null ? 0 : user.scopeVersion;
        return jwtService.issue(user.id, role, openid, client, sid, sv);
    }

    private Map<String, Object> userInfo(Employee user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.id);
        m.put("name", user.name);
        m.put("phone", user.phone);
        m.put("account", user.account);
        m.put("role", user.role);
        m.put("roleText", user.roleText);
        m.put("platformId", user.platformId);
        m.put("certificate", user.certificate);
        m.put("workStartDate", user.workStartDate);
        m.put("workEndDate", user.workEndDate);
        m.put("workStat", user.workStat);
        m.put("syncStatus", user.syncStatus);
        return m;
    }

    private Employee currentUser(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = jwtService.verify(auth.substring(7).trim());
            return employeeMapper.selectById(claims.getSubject());
        } catch (BizException e) {
            return null;
        }
    }
}
