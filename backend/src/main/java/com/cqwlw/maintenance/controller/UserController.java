package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 停用吊销 token（bumpScopeVersion）。 */
@RestController
public class UserController {

    private final EmployeeMapper employeeMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    public UserController(EmployeeMapper employeeMapper, SysUserRoleMapper userRoleMapper) {
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    private static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.USER_VIEW);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<Employee>().orderByAsc(Employee::getId))) {
            List<String> roles = rolesOf(e.id);
            Set<String> effective = new LinkedHashSet<>(roles);
            if (e.role != null && !e.role.isBlank()) {
                effective.add(e.role);
            }
            if (RoleCatalog.isCustomerPrincipal(effective)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id);
            row.put("name", e.name);
            row.put("phone", e.phone);
            row.put("department", e.department);
            row.put("status", Boolean.FALSE.equals(e.enabled) ? "DISABLED" : "NORMAL");
            row.put("roles", roles);
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.USER_CREATE);
        String name = str(body, "name");
        String phone = str(body, "phone");
        List<String> roles = strList(body, "roles");
        if (name == null || name.isBlank() || phone == null || !phone.matches("^1[3-9]\\d{9}$") || roles.isEmpty()) {
            throw new BizException(422, "姓名/手机号/角色必填且格式正确");
        }
        validateSameDepartment(roles);
        requireInternalRoles(roles);
        requireAssignableRoles(req, roles);
        Employee e = new Employee();
        e.id = Ids.next("emp");
        e.name = name;
        e.phone = phone;
        e.account = phone;
        String rawPwd = Ids.next("pwd");
        e.passwordHash = enc.encode(rawPwd);
        e.role = roles.get(0);
        e.roleText = RoleCatalog.nameOf(roles.get(0));
        e.department = RoleCatalog.canonicalRole(roles.get(0)).department();
        e.enabled = true;
        e.workStat = "normal";
        e.syncStatus = "NOT_SYNCED";
        employeeMapper.insert(e);
        for (String r : roles) {
            insertRole(e.id, r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id);
        out.put("initialPassword", rawPwd);
        return ApiResponse.ok(out);
    }

    @PostMapping("/users/{id}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.USER_DISABLE);
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "账号不存在");
        }
        requireManageableTarget(req, e);
        e.enabled = false;
        employeeMapper.updateById(e);
        employeeMapper.bumpScopeVersion(id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/users/{id}/roles")
    public ApiResponse<Map<String, Object>> assignRoles(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ROLE_ASSIGN);
        List<String> roles = strList(body, "roles");
        if (roles.isEmpty()) {
            throw new BizException(422, "至少一个角色");
        }
        validateSameDepartment(roles);
        requireInternalRoles(roles);
        requireAssignableRoles(req, roles);
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(404, "账号不存在");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        for (String r : roles) {
            insertRole(id, r);
        }
        e.role = roles.get(0);
        e.roleText = RoleCatalog.nameOf(roles.get(0));
        e.department = RoleCatalog.canonicalRole(roles.get(0)).department();
        employeeMapper.updateById(e);
        employeeMapper.bumpScopeVersion(id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    private void requireAssignableRoles(HttpServletRequest req, List<String> roles) {
        Set<String> actorRoles = new LinkedHashSet<>();
        for (String role : MultiRoleGate.roles(req)) {
            String canonical = RoleCatalog.canonical(role);
            if (canonical != null) {
                actorRoles.add(canonical);
            }
        }
        if (actorRoles.contains("PLATFORM_ADMIN")) {
            return;
        }
        for (String code : roles) {
            RoleCatalog.Role target = RoleCatalog.canonicalRole(code);
            boolean allowed = target != null && !"PLATFORM_ADMIN".equals(target.code())
                    && actorRoles.stream().anyMatch(actor -> canAssign(actor, target));
            if (!allowed) {
                throw new BizException(1403, "只能授予本部门且不超出既有授权层级的角色");
            }
        }
    }

    private static boolean canAssign(String actorCode, RoleCatalog.Role target) {
        RoleCatalog.Role actor = RoleCatalog.canonicalRole(actorCode);
        if (actor == null || !java.util.Objects.equals(actor.department(), target.department())) {
            return false;
        }
        if (actor.code().equals(target.code())) {
            return true;
        }
        Set<String> visited = new LinkedHashSet<>();
        String parent = target.parentRole();
        while (parent != null && visited.add(parent)) {
            if (actor.code().equals(parent)) {
                return true;
            }
            RoleCatalog.Role parentRole = RoleCatalog.canonicalRole(parent);
            parent = parentRole == null ? null : parentRole.parentRole();
        }
        return false;
    }

    private void requireManageableTarget(HttpServletRequest req, Employee target) {
        if (target.id.equals(actor(req))) {
            throw new BizException(422, "不能停用本人账号");
        }
        Set<String> actorRoles = new LinkedHashSet<>();
        for (String role : MultiRoleGate.roles(req)) {
            String canonical = RoleCatalog.canonical(role);
            if (canonical != null) {
                actorRoles.add(canonical);
            }
        }
        if (actorRoles.contains("PLATFORM_ADMIN")) {
            return;
        }
        Set<String> targetRoles = new LinkedHashSet<>(rolesOf(target.id));
        targetRoles.add(target.role);
        boolean platformTarget = targetRoles.stream().anyMatch(RoleCatalog::isPlatformRole);
        String targetDepartment = target.department == null || target.department.isBlank()
                ? RoleCatalog.departmentOf(target.role) : target.department;
        boolean sameDepartment = actorRoles.stream()
                .map(RoleCatalog::canonicalRole)
                .filter(java.util.Objects::nonNull)
                .anyMatch(role -> java.util.Objects.equals(role.department(), targetDepartment));
        if (platformTarget || !sameDepartment) {
            throw new BizException(1403, "不能停用平台管理员或跨部门账号");
        }
    }

    private List<String> rolesOf(String userId) {
        List<String> out = new ArrayList<>();
        for (SysUserRole ur : userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))) {
            out.add(ur.roleCode);
        }
        return out;
    }

    private void insertRole(String userId, String role) {
        SysUserRole ur = new SysUserRole();
        ur.id = Ids.next("ur");
        ur.userId = userId;
        ur.userType = "EMPLOYEE";
        ur.roleCode = role;
        ur.createdAt = TimeUtil.now();
        userRoleMapper.insert(ur);
    }

    private static void requireInternalRoles(List<String> roles) {
        for (String r : roles) {
            if (RoleCatalog.principalTypeOf(r) == RoleCatalog.PrincipalType.CUSTOMER) {
                throw new BizException(422,
                        "使用单位客户账号不在人员目录开通，请在「使用单位档案 → 客户账号」中开通");
            }
        }
    }

    private static void validateSameDepartment(List<String> roles) {
        String dept = null;
        boolean first = true;
        for (String r : roles) {
            RoleCatalog.Role cr = RoleCatalog.canonicalRole(r);
            if (cr == null) {
                throw new BizException(422, "未知角色: " + r);
            }
            String d = cr.department(); // P1：客户角色部门为 null（同为外部租户，视为同域）
            if (first) {
                dept = d;
                first = false;
            } else if (!java.util.Objects.equals(dept, d)) {
                throw new BizException(422, "跨部门兼任禁止（R7）");
            }
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }
}