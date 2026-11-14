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
import java.util.List;
import java.util.Map;

/**
 * M3 账号管理：列表/开通/停用/分配角色。多角色同部门校验（R7）；停用吊销 token（bumpScopeVersion）。
 */
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
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id);
            row.put("name", e.name);
            row.put("phone", e.phone);
            row.put("department", e.department);
            row.put("status", Boolean.FALSE.equals(e.enabled) ? "DISABLED" : "NORMAL");
            row.put("roles", rolesOf(e.id));
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
        Employee e = new Employee();
        e.id = Ids.next("emp");
        e.name = name;
        e.phone = phone;
        e.account = phone;
        String rawPwd = Ids.next("pwd");
        e.passwordHash = enc.encode(rawPwd);
        e.role = roles.get(0);
        e.roleText = RoleCatalog.nameOf(roles.get(0));
        e.department = RoleCatalog.departmentOf(roles.get(0));
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
        e.department = RoleCatalog.departmentOf(roles.get(0));
        employeeMapper.updateById(e);
        employeeMapper.bumpScopeVersion(id);
        return ApiResponse.ok(Map.of("ok", true));
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

    private static void validateSameDepartment(List<String> roles) {
        String dept = null;
        for (String r : roles) {
            String canonical = RoleCatalog.canonical(r);
            if (canonical == null) {
                throw new BizException(422, "未知角色: " + r);
            }
            String d = RoleCatalog.departmentOf(canonical);
            if (dept == null) {
                dept = d;
            } else if (!dept.equals(d)) {
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