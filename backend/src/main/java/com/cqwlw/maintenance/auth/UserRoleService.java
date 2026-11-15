package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户规范角色集（M1，规格 §3.2）：sys_user_role（EMPLOYEE）∪ 别名归一后的旧主角色。
 * 多角色权限取并集；跨部门禁止兼任由 R7 在授予侧校验（V41 合并同规则）。
 */
@Service
public class UserRoleService {

    private final SysUserRoleMapper userRoleMapper;

    public UserRoleService(SysUserRoleMapper userRoleMapper) {
        this.userRoleMapper = userRoleMapper;
    }

    public Set<String> canonicalRolesOf(String userId, String legacyRole) {
        Set<String> out = new LinkedHashSet<>();
        for (SysUserRole r : userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getUserType, "EMPLOYEE"))) {
            out.add(RoleCatalog.canonical(r.roleCode));
        }
        if (legacyRole != null && !legacyRole.isEmpty()) {
            out.add(RoleCatalog.canonical(legacyRole));
        }
        return out;
    }
}