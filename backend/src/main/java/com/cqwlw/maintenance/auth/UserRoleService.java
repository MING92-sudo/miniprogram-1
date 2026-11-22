package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

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