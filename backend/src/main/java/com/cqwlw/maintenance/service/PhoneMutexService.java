package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机号互斥预校验，校验前移至建档期（平台不校验，由本系统承担）。互斥范围仅五个角色：
 * 使用单位负责人 / 使用单位安全管理员 / 维保经理 / 维保人员1 / 维保人员2，
 * emergencyPhone 与 recorderPhone 不参与。同一手机号出现在 ≥2 个不同互斥角色上即冲突
 * （同人同角色不算），返回 1002 + conflicts[]。
 */
@Service
public class PhoneMutexService {

    /** 参与互斥的一个"角色-归属-手机号"三元组 */
    public record Party(String role, String owner, String phone) {
    }

    private final CompanyMapper companyMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;

    public PhoneMutexService(CompanyMapper companyMapper, UseUnitMapper useUnitMapper,
                             EmployeeMapper employeeMapper) {
        this.companyMapper = companyMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
    }

    /**
     * 收集系统内既有的互斥角色手机号（排除被保存实体自身的旧值）。
     * 电梯表不参与收集：workerPhone 均来源于员工档案，避免同角色同人误报。
     */
    public List<Party> globalParties(String excludeType, String excludeId) {
        List<Party> parties = new ArrayList<>();
        Company c = companyMapper.selectList(null).stream().findFirst().orElse(null);
        if (c != null && nz(c.workMenegerPhone) != null) {
            parties.add(new Party("维保经理", c.name, c.workMenegerPhone));
        }
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<>())) {
            String phone = nz(e.phone);
            if (phone == null) {
                continue;
            }
            // 注意：roleOf 可能返回 null（ADMIN/SYS_ADMIN 不对应 2.6 角色），不能用 switch(null)
            String mutexRole = roleOf(e.role);
            if ("维保人员".equals(mutexRole)) {
                parties.add(new Party("维保人员", e.name, phone));
            } else if ("使用单位安全管理员".equals(mutexRole)) {
                parties.add(new Party("使用单位安全管理员", e.name, phone));
            }
            // 其余（ADMIN/SYS_ADMIN）不参与互斥
        }
        for (UseUnit u : useUnitMapper.selectList(new LambdaQueryWrapper<>())) {
            if ("use-unit".equals(excludeType) && u.id != null && u.id.equals(excludeId)) {
                continue;
            }
            if (nz(u.unitPrincipalPhone) != null) {
                parties.add(new Party("使用单位负责人", u.unitName, u.unitPrincipalPhone));
            }
            if (nz(u.elevatorAdministerPhone) != null) {
                parties.add(new Party("使用单位安全管理员", u.unitName, u.elevatorAdministerPhone));
            }
        }
        return parties;
    }

    /**
     * 纯函数：检测冲突。同一手机号出现于 ≥2 个不同互斥角色 → 冲突对。
     * 返回形如 [{phone, roles:[{role, owner}...]}]；空列表 = 通过。
     */
    public static List<Map<String, Object>> findConflicts(List<Party> parties) {
        Map<String, List<Party>> byPhone = new LinkedHashMap<>();
        for (Party p : parties) {
            String phone = nz(p.phone());
            if (phone == null) {
                continue;
            }
            byPhone.computeIfAbsent(phone, k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Party>> en : byPhone.entrySet()) {
            List<Party> group = en.getValue();
            List<Map<String, Object>> roles = new ArrayList<>();
            List<String> roleLabels = new ArrayList<>();
            for (Party p : group) {
                if (!roleLabels.contains(p.role())) {
                    roleLabels.add(p.role());
                    // Map.of 不接受 null 值：owner 允许为空，展示统一转空串
                    String owner = p.owner() == null ? "" : p.owner();
                    roles.add(Map.of("role", p.role(), "owner", owner));
                }
            }
            if (roleLabels.size() >= 2) {
                conflicts.add(Map.of("phone", en.getKey(), "roles", roles));
            }
        }
        return conflicts;
    }

    /** 使用单位保存预校验：负责人 + 安全管理员 vs 全局 */
    public List<Map<String, Object>> checkUseUnit(String useUnitId, String unitPrincipalPhone,
                                                  String elevatorAdministerPhone) {
        List<Party> parties = new ArrayList<>(globalParties("use-unit", useUnitId));
        if (nz(unitPrincipalPhone) != null) {
            parties.add(new Party("使用单位负责人", "", unitPrincipalPhone));
        }
        if (nz(elevatorAdministerPhone) != null) {
            parties.add(new Party("使用单位安全管理员", "", elevatorAdministerPhone));
        }
        return findConflicts(parties);
    }

    /** 人员建档/修改预校验（ADMIN/SYS_ADMIN 账号不参与互斥） */
    public List<Map<String, Object>> checkEmployee(String employeeId, String role, String phone) {
        String mutexRole = roleOf(role);
        if (mutexRole == null) {
            return List.of();
        }
        List<Party> parties = new ArrayList<>(globalParties("employee", employeeId));
        parties.add(new Party(mutexRole, "", phone));
        return findConflicts(parties);
    }

    /** 电梯保存预校验：维保人员手机 vs 全局 */
    public List<Map<String, Object>> checkElevator(String workerPhone) {
        if (nz(workerPhone) == null) {
            return List.of();
        }
        List<Party> parties = new ArrayList<>(globalParties(null, null));
        parties.add(new Party("维保人员", "", workerPhone));
        return findConflicts(parties);
    }

    /** 维保单位保存预校验：维保经理手机 vs 全局 */
    public List<Map<String, Object>> checkCompany(String workMenegerPhone) {
        List<Party> parties = new ArrayList<>(globalParties("company", null));
        if (nz(workMenegerPhone) != null) {
            parties.add(new Party("维保经理", "", workMenegerPhone));
        }
        return findConflicts(parties);
    }

    private static String roleOf(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case AdminRoles.WORKER, AdminRoles.LEADER -> "维保人员";
            case AdminRoles.UNIT_ADMIN -> "使用单位安全管理员";
            default -> null;
        };
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
