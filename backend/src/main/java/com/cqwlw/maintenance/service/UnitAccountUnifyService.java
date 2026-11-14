package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * V41 门户账号并入员工体系（M1，规格 §3.1）：use_unit 门户账号 → sys_employee + sys_user_role(CLIENT_ADMIN)。
 * 合并规则：同部门（UNIT/空）合并追加角色；跨部门不合并（R7 硬规则，account_migrated=0 保留门户兜底）；
 * 密码优先不覆盖；openid 已被占用不抢夺（uk_openid）。只 INSERT/UPDATE 标记行，不 DELETE（留存红线）。
 * 默认只授 CLIENT_ADMIN；CLIENT_SIGNER 须显式授予（蓝图「须显式持有」，裁决 R3）。
 */
@Service
public class UnitAccountUnifyService {

    private static final Logger log = LoggerFactory.getLogger(UnitAccountUnifyService.class);

    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final SysUserRoleMapper userRoleMapper;

    public UnitAccountUnifyService(UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                                   SysUserRoleMapper userRoleMapper) {
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @PostConstruct
    public void onStartup() {
        int n = unifyPending();
        if (n > 0) {
            log.info("门户账号统一：本次并入 {} 个单位账号", n);
        }
    }

    @Transactional
    public int unifyPending() {
        int n = 0;
        for (UseUnit u : useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>()
                .isNotNull(UseUnit::getAccount).eq(UseUnit::getAccountMigrated, 0))) {
            Employee exist = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                    .eq(Employee::getAccount, u.account).last("LIMIT 1"));
            if (exist != null && exist.department != null && !"UNIT".equals(exist.department)) {
                continue; // R7：跨部门禁止兼任，保持未迁移兜底
            }
            Employee emp = exist;
            if (emp == null) {
                emp = new Employee();
                emp.id = Ids.next("emp");
                emp.account = u.account;
                emp.phone = u.account;
                emp.name = u.unitName == null ? u.account : u.unitName;
                emp.passwordHash = u.passwordHash;
                emp.department = "UNIT";
                emp.role = "CLIENT_ADMIN";
                emp.roleText = "使用单位管理员";
                emp.scopeVersion = 0;
                emp.enabled = true;
                if (u.openid != null && employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getOpenid, u.openid)) == 0) {
                    emp.openid = u.openid;
                }
                employeeMapper.insert(emp);
            }
            insertRoleIfAbsent(emp.id, "CLIENT_ADMIN", u.id);
            u.accountMigrated = 1;
            useUnitMapper.updateById(u);
            n++;
        }
        return n;
    }

    private void insertRoleIfAbsent(String userId, String roleCode, String orgId) {
        Long held = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getUserType, "EMPLOYEE")
                .eq(SysUserRole::getRoleCode, roleCode));
        if (held != null && held > 0) {
            return;
        }
        SysUserRole row = new SysUserRole();
        row.id = Ids.next("ur");
        row.userId = userId;
        row.userType = "EMPLOYEE";
        row.roleCode = roleCode;
        row.orgId = orgId;
        row.createdAt = LocalDateTime.now();
        userRoleMapper.insert(row);
    }
}