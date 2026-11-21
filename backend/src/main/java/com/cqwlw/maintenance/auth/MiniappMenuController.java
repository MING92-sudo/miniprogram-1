package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 小程序菜单下发端点（M1，规格 §3.4）：GET /me/menus?workspace=。
 * 独立控制器避免改动 AuthController 构造器波及既有测试。
 * 这是**唯一**菜单下发端点：旧的 GET /auth/menus（MenuService）已于 2026-11-20 删除——
 * 管理端菜单由 admin/src/ui/menu-config.js + permission-v2 本地投影，不需要服务端下发。
 */
@RestController
public class MiniappMenuController {

    private final MiniappMenuService menuService;
    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;

    public MiniappMenuController(MiniappMenuService menuService, EmployeeMapper employeeMapper,
                                 JwtService jwtService) {
        this.menuService = menuService;
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
    }

    @GetMapping("/me/menus")
    public ApiResponse<Map<String, Object>> meMenus(HttpServletRequest request,
                                                    @RequestParam(required = false) String workspace) {
        // 客户主体（ut=2，subject=client_account.id）不在员工目录里，不能走 employeeMapper 查人，
        // 更不能抛 401：前端 request 层把 401 当「登录已失效」→ clearAuth + 回登录页，
        // 于是客户登录成功后立刻被踢回登录页（小程序实测必现）。客户菜单走 clientMenus 真实下发。
        Object principalType = request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE);
        if (RoleCatalog.PrincipalType.CUSTOMER.name().equals(String.valueOf(principalType))) {
            return ApiResponse.ok(clientMenus(request));
        }
        Employee me = currentUser(request);
        if (me == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Set<String> roles = MultiRoleGate.roles(request);
        return ApiResponse.ok(menuService.menusOf(roles, workspace, me));
    }

    /**
     * 客户主体菜单（2026-11-21 修复）：不再返回空 stub——tabs/quickActions 由 MiniappMenuService
     * 按客户角色集真实下发（与员工端同一 ROLE_TABS/快捷动作口径）。空 stub 曾被前端 applyToGlobal
     * 无条件覆盖，冷启动/时序竞争下表现为底栏与快捷动作整块消失（真机反馈）。
     */
    private Map<String, Object> clientMenus(HttpServletRequest request) {
        Object rolesAttr = request.getAttribute(AuthInterceptor.ATTR_ROLES);
        @SuppressWarnings("unchecked")
        Set<String> roles = rolesAttr instanceof Set ? (Set<String>) rolesAttr : Set.of();
        Object accountId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        return menuService.clientMenusOf(roles, accountId == null ? null : String.valueOf(accountId));
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