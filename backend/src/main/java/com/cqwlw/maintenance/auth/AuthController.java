package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.ApiResponse;
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
 * 认证：账号密码登录（BCrypt）→ 自建 JWT；
 * 微信侧一律由 wx.login 的 code 经 jscode2session 换真实 openid（bind-wechat 落库 / wx-login 查账号）。
 *
 * <p>openid 只有一个来源——后端 jscode2session（WxAuthService）。
 * <ol>
 *   <li>不再回退 {@code app.dev-openid}。历史缺陷：两个接口只读 X-WX-OPENID，缺失时写 dev_openid，
 *       全员同一 openid，/auth/wx-login 按 openid LIMIT 1 查员工 → 互相登入对方账号（串号）。</li>
 *   <li>不再信任可伪造的 {@code X-WX-OPENID} 请求头。该头仅云托管「小程序免鉴权调用」注入，
 *       方案 A（wx.request 直连域名）下任何 HTTP 客户端都能自带，等于"给一个 openid 就签发该账号 JWT"
 *       的越权原语；方案 B 客户端同样能传 code，故主路径收紧为必须有 code。</li>
 *   <li>JWT 不再携带 openid 声明：无任何消费方（AuthInterceptor 只透传属性，无业务读取），
 *       避免客户端可控值进入签名令牌。</li>
 * </ol>
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;
    private final WxAuthService wxAuthService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(EmployeeMapper employeeMapper, JwtService jwtService, WxAuthService wxAuthService) {
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
        this.wxAuthService = wxAuthService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String phone = body.get("phone") == null ? "" : String.valueOf(body.get("phone")).trim();
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getAccount, phone).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(401, "账号不存在，请联系维保单位管理员分配");
        }
        if (!encoder.matches(password, user.passwordHash)) {
            throw new BizException(401, "账号或密码错误");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        // 管理端会话白名单：client=admin 时仅 LEADER/ADMIN/SYS_ADMIN 可登录；
        // 小程序端（不带 client）不受影响。
        if ("admin".equals(String.valueOf(body.get("client")))) {
            if (!AdminRoles.canUseAdminConsole(user.role)) {
                throw new BizException(403, "该账号无管理端权限，请使用小程序登录");
            }
        }
        return ApiResponse.ok(loginResult(user));
    }

    /**
     * 微信绑定（登录后调用，需 Bearer）。openid 由 code 换取并写入当前账号。
     * 事务：先解绑同一 openid 的其他账号、再写当前账号，两写必须同成同败（否则解绑成功、
     * 绑定失败会让原账号丢掉微信登录）。
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
        return ApiResponse.ok(Map.of("ok", true, "openid", openid));
    }

    @PostMapping("/auth/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user == null) {
            // 422 而非 401：前端 utils/request.js 对 401 会清登录态，一键登录入口若在已登录页面调用会误登出
            throw new BizException(422, "该微信尚未绑定账号，请先用账号密码登录并绑定微信");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        return ApiResponse.ok(loginResult(user));
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

    private Map<String, Object> roleLogin(HttpServletRequest request, String requestedRole, boolean forceUnitAdmin) {
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
        return loginResult(current);
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private Map<String, Object> loginResult(Employee user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwtService.issue(user.id, user.role, null));
        out.put("userInfo", userInfo(user));
        out.put("role", user.role);
        return out;
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
