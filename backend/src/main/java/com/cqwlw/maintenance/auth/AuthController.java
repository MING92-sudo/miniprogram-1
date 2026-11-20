package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.ClientLoginService;
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
import java.util.List;
import java.util.Map;

/**
 * 认证（docs/04 A.1 契约）：账号密码登录（BCrypt）→ 自建 JWT。
 *
 * <p><b>唯一登录入口</b>（用户需求）：小程序只有一个登录页，不选通道、不选角色。
 * {@code POST /auth/login} 按账号自动判定主体——员工目录（sys_employee）命中即内部人员，
 * 否则按客户账号（client_account）校验；两端各自签发令牌，响应统一带 principalType，
 * 前端据 home/principalType 直接渲染对应工作台。微信登录 {@code POST /auth/wx-login} 同口径。
 *
 * <p>openid 只有一个来源——后端 jscode2session（WxAuthService）：不回退 app.dev-openid，
 * 也不信任可伪造的 X-WX-OPENID 请求头。
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;
    private final WxAuthService wxAuthService;
    /** 统一登录的客户分支（client_account：口令/微信/多单位选择） */
    private final ClientLoginService clientLoginService;
    /** M1：规范角色集（sys_user_role ∪ 旧主角色别名归一） */
    private final UserRoleService userRoleService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 客户账号目录（setter 注入：保持既有构造器签名，不波及已有测试） */
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;
    /** 客户账号自助能力（改密/绑微信）：白名单端点接待客户主体时委托给它 */
    private com.cqwlw.maintenance.service.ClientAccountService clientAccountService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper) {
        this.clientAccountMapper = clientAccountMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountService(com.cqwlw.maintenance.service.ClientAccountService clientAccountService) {
        this.clientAccountService = clientAccountService;
    }

    /**
     * 取当前客户主体账号（client_account）。
     * 客户令牌的 subject 是 client_account.id，不在员工目录里——这条路径不得回落到 employeeMapper，
     * 否则会返回 401 让前端清登录态（BUG-1 的成因）。
     */
    private com.cqwlw.maintenance.entity.ClientAccount requireClientAccount(HttpServletRequest request) {
        Object id = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        com.cqwlw.maintenance.entity.ClientAccount c = id == null || clientAccountMapper == null
                ? null : clientAccountMapper.selectById(String.valueOf(id));
        if (c == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return c;
    }

    public AuthController(EmployeeMapper employeeMapper, JwtService jwtService, WxAuthService wxAuthService,
                          ClientLoginService clientLoginService,
                          UserRoleService userRoleService) {
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
        this.wxAuthService = wxAuthService;
        this.clientLoginService = clientLoginService;
        this.userRoleService = userRoleService;
    }

    /**
     * 账号密码登录（唯一入口）：自动识别内部员工 / 使用单位客户。
     * 判定顺序：员工目录（按账号）→ 客户账号（按手机号）→ 401。
     * 手机号在两侧互斥（docs/14 §6.1），不会歧义。
     */
    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String account = body.get("phone") == null ? "" : String.valueOf(body.get("phone")).trim();
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        if (account.isEmpty() || password.isEmpty()) {
            throw new BizException(422, "请输入账号和密码");
        }
        boolean adminConsole = "admin".equals(String.valueOf(body.get("client")));

        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getAccount, account).last("LIMIT 1"));
        // 历史残留的客户角色员工行不算内部人员：统一走客户账号校验（旧口令不再有效）
        if (user != null && !isCustomerPrincipal(user)) {
            if (!encoder.matches(password, user.passwordHash)) {
                throw new BizException(401, "账号或密码错误");
            }
            if (Boolean.FALSE.equals(user.enabled)) {
                throw new BizException(403, "账号已停用，请联系系统管理员");
            }
            // 管理端会话白名单（docs/09 §3.2）：client=admin 仅 ADMIN/SYS_ADMIN/PLATFORM_ADMIN；
            // 小程序端（不带 client）不受影响。
            if (adminConsole && !AdminRoles.canUseAdminConsole(user.role)) {
                throw new BizException(403, "该账号无管理端权限，请使用小程序登录");
            }
            return ApiResponse.ok(loginResult(user, null, adminConsole ? "admin" : "mp"));
        }

        if (clientLoginService.hasAnyAccount(account)) {
            if (adminConsole) {
                throw new BizException(403, "使用单位客户账号请使用小程序登录");
            }
            return ApiResponse.ok(clientLoginService.loginByPassword(account, password));
        }

        if (user != null) {
            // 全客户角色但 client_account 无号：旧门户链已停用，提示去开通，不再签发员工令牌
            throw new BizException(403, "该客户账号未开通，请联系维保单位在使用单位档案中开通客户账号");
        }
        throw new BizException(401, "账号或密码错误");
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
        // 客户主体（白名单放行 /auth/bind-wechat）：委托 client_account 口径，不得查员工表回 401
        if (AuthInterceptor.isCustomerRequest(request)) {
            com.cqwlw.maintenance.entity.ClientAccount account = requireClientAccount(request);
            if (clientAccountService == null) {
                throw new BizException(500, "客户账号服务未就绪");
            }
            String customerOpenid = wxAuthService.openidFromCode(text(body.get("code")));
            clientAccountService.bindWechat(account.id, customerOpenid, account.id);
            return ApiResponse.ok(Map.of("ok", true));
        }
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

    /** 微信一键登录（唯一入口）：员工 openid 命中即员工；否则按客户账号 openid 校验。 */
    @PostMapping("/auth/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user != null && !isCustomerPrincipal(user)) {
            if (Boolean.FALSE.equals(user.enabled)) {
                throw new BizException(403, "账号已停用，请联系系统管理员");
            }
            return ApiResponse.ok(loginResult(user, null, "mp"));
        }
        if (clientLoginService.hasAnyAccountByOpenid(openid)) {
            return ApiResponse.ok(clientLoginService.loginByOpenid(openid));
        }
        // 422 而非 401：前端 request.js 对 401 会清登录态，一键登录入口若在已登录页面调用会误登出
        throw new BizException(422, "该微信尚未绑定账号，请先用账号密码登录并绑定微信");
    }

    /** 生效角色集全为客户角色 → 客户主体（RoleCatalog 口径，与登录响应 principalType 同源） */
    private boolean isCustomerPrincipal(Employee user) {
        return RoleCatalog.isCustomerPrincipal(userRoleService.canonicalRolesOf(user.id, user.role));
    }

    /** 用户需求④：登录用户自助改密（新密码 ≥6 位；既有 JWT 不失效，重新登录用新密码） */
    @PostMapping("/auth/change-password")
    public ApiResponse<Map<String, Object>> changePassword(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        String oldPassword = body.get("oldPassword") == null ? "" : String.valueOf(body.get("oldPassword"));
        String newPassword = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        // 客户主体（白名单放行 /auth/change-password）：改 client_account.password_hash。
        // 此前落到 employeeMapper 查询为空 → 401 → 前端清登录态，客户在个人中心改密必被踢。
        if (AuthInterceptor.isCustomerRequest(request)) {
            com.cqwlw.maintenance.entity.ClientAccount account = requireClientAccount(request);
            if (clientAccountService == null) {
                throw new BizException(500, "客户账号服务未就绪");
            }
            clientAccountService.changePassword(account.id, oldPassword, newPassword);
            return ApiResponse.ok(Map.of("ok", true));
        }
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
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

    /**
     * 忘记密码（登录页入口，免登录）：code 换 openid 匹配账号后重置；重置后清会话。
     * 员工与客户同一入口：先查员工绑定，再查客户账号绑定。
     */
    @PostMapping("/auth/reset-password-by-wechat")
    @Transactional
    public ApiResponse<Map<String, Object>> resetPasswordByWechat(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        String newPassword = text(body.get("newPassword"));
        if (newPassword.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user != null && !isCustomerPrincipal(user)) {
            if (Boolean.FALSE.equals(user.enabled)) {
                throw new BizException(403, "账号已停用，请联系系统管理员");
            }
            user.passwordHash = encoder.encode(newPassword);
            employeeMapper.updateById(user);
            employeeMapper.clearSessions(user.id);
            log.info("微信验证重置密码成功: empId={}", user.id); // 不打印 openid/新密码（脱敏）
            return ApiResponse.ok(Map.of("ok", true, "account", user.account == null ? "" : user.account,
                    "principalType", RoleCatalog.PrincipalType.EMPLOYEE.name()));
        }
        List<ClientAccount> clients = clientLoginService.activeAccountsByOpenid(openid);
        if (!clients.isEmpty()) {
            ClientAccount c = clients.get(0);
            clientLoginService.resetPasswordByWechat(c.id, newPassword);
            log.info("微信验证重置客户密码成功: accountId={}", c.id);
            return ApiResponse.ok(Map.of("ok", true, "account", c.phone,
                    "principalType", RoleCatalog.PrincipalType.CUSTOMER.name()));
        }
        throw new BizException(422, "该微信尚未绑定账号，请联系单位管理员重置密码");
    }

    /**
     * 注销：吊销当前会话，旧令牌立即失效（修复前为空实现——登出后令牌在有效期内仍可继续调用）。
     * 员工：清 sid（小程端/管理端两枚会话都断）+ 递增 scope_version；
     * 客户：清 sid + 递增 scope_version。幂等，不因令牌主体已被删除而失败。
     */
    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        // 本端点免鉴权（登出时令牌可能刚过期），主体直接从 Authorization 里的 JWT 解析；
        // 令牌无效/已过期视为无需吊销，仍返回 ok（幂等，前端不必区分）。
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            Claims claims;
            try {
                claims = jwtService.verify(auth.substring(7));
            } catch (RuntimeException invalidToken) {
                // 令牌损坏或已过期：无会话可吊销，仍返回 ok（幂等）
                return ApiResponse.ok(Map.of("ok", true));
            }
            // 吊销动作放在 try 之外：令牌有效但吊销失败必须暴露为错误，
            // 否则接口谎报登出成功、旧令牌继续可用——正是本端点修复前的缺陷形态。
            String subject = claims.getSubject();
            if (subject != null && !subject.isBlank()) {
                if ("2".equals(claims.get("ut", String.class))) {
                    clientLoginService.revokeSession(subject);
                } else {
                    Employee e = employeeMapper.selectById(subject);
                    if (e != null) {
                        int nextSv = (e.scopeVersion == null ? 0 : e.scopeVersion) + 1;
                        // updateById 会跳过 null 字段，sid 必须用 UpdateWrapper 显式置空
                        employeeMapper.update(null, new LambdaUpdateWrapper<Employee>()
                                .eq(Employee::getId, subject)
                                .set(Employee::getSessionId, null)
                                .set(Employee::getSessionAdmin, null)
                                .set(Employee::getScopeVersion, nextSv));
                    }
                }
            }
        }
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        // 客户主体：subject=client_account.id，不在员工目录里，不能查 employeeMapper 后抛 401。
        // 该端点在客户端白名单内（AuthInterceptor.clientAllowed），返回 401 会让调用方清登录态。
        if (AuthInterceptor.isCustomerRequest(request)) {
            return ApiResponse.ok(customerInfo(request));
        }
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        return ApiResponse.ok(Map.of("userInfo", userInfo(user), "role", role));
    }

    /** 客户主体本人信息（与 /auth/client/me 同口径，供共用调用方使用） */
    private Map<String, Object> customerInfo(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        Object accountId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        String id = accountId == null ? "" : String.valueOf(accountId);
        info.put("id", id);
        if (clientAccountMapper != null && !id.isEmpty()) {
            com.cqwlw.maintenance.entity.ClientAccount account = clientAccountMapper.selectById(id);
            if (account != null) {
                info.put("name", account.name);
                info.put("phone", account.phone);
                info.put("useUnitId", account.useUnitId);
                info.put("status", account.status);
            }
        }
        out.put("userInfo", info);
        out.put("role", request.getAttribute(AuthInterceptor.ATTR_ROLE) == null
                ? "" : String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE)));
        out.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        return out;
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
        // P1 T8-A2：主体类型（与 mock loginPayload.principalType 同口径）——生效角色集全为客户角色
        // → CUSTOMER，否则 EMPLOYEE；小程序据此收敛客户入口（profile 管理入口/守卫判定）
        out.put("principalType", (RoleCatalog.isCustomerPrincipal(roles)
                ? RoleCatalog.PrincipalType.CUSTOMER
                : RoleCatalog.PrincipalType.EMPLOYEE).name());
        // 渲染面 perms=Y∪C（C 级读页可渲染可达，数据范围由 ScopeResolver 谓词强制）；按钮 actions 仍 Y-only
        out.put("perms", PermissionMatrix.canonicalCapabilities().stream()
                .filter(c -> roles.stream().anyMatch(r -> PermissionMatrix.state(r, c) != PermissionMatrix.TriState.DENY))
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
