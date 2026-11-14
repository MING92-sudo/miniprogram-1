package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysRoleScope;
import com.cqwlw.maintenance.mapper.SysRoleScopeMapper;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 6 范围读写双谓词门面（spec §4.4）。旧角色委托 EmployeeScopeService（V7 不动，证等价）；
 * 新角色 INST_MGR(ORG)/CLIENT_SIGNER(ORG_FLAT) 按 sys_role_scope 默认范围解析，不在 V7 等价靶内。
 * 读写分离红线：范围扩大只进读谓词，写严格本人/本组/被指派（AGENTS §3.1）。
 */
@Service
public class ScopeResolver {

    private final EmployeeScopeService employeeScope;
    private final DeptScopeService deptScope;
    private final SysRoleScopeMapper roleScopeMapper;

    public ScopeResolver(EmployeeScopeService employeeScope, DeptScopeService deptScope,
                         SysRoleScopeMapper roleScopeMapper) {
        this.employeeScope = employeeScope;
        this.deptScope = deptScope;
        this.roleScopeMapper = roleScopeMapper;
    }

    public Set<String> readPlatformIds(Employee e) {
        String canonical = RoleCatalog.canonical(e.role);
        if ("INST_MGR".equals(canonical)) {
            return null; // ORG：安装部及下级全量读（新角色，无 V7 对应）
        }
        return employeeScope.visibleReadPlatformIds(e);
    }

    public Set<String> writePlatformIds(Employee e) {
        String canonical = RoleCatalog.canonical(e.role);
        if ("INST_MGR".equals(canonical)) {
            // 写仍严格本人（防代签到/代签退）；ORG 只扩读
            return e.platformId == null || e.platformId.isBlank() ? Set.of() : Set.of(e.platformId);
        }
        return employeeScope.visibleWorkerPlatformIds(e);
    }

    /** 门户域本单位收敛（CLIENT_ADMIN/CLIENT_SIGNER）；委托 DeptScopeService.useUnitScope。 */
    public String unitScope(Employee e) {
        return deptScope.useUnitScope(e);
    }

    /** 角色默认范围枚举（sys_role_scope）；未知 null。 */
    public String defaultScope(String canonicalRole) {
        if (canonicalRole == null) {
            return null;
        }
        SysRoleScope rs = roleScopeMapper.selectById(canonicalRole);
        return rs == null ? null : rs.scope;
    }
}