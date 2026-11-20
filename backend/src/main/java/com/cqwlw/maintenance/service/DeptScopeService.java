package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门域数据谓词（docs/14 §5）：平台 / 部门 / 上级链 / 班组 / 本人 / 使用单位六种可见范围，
 * 由业务查询显式声明用哪一种（docs/14 §8 R10：列表过滤 + 详情校验双保险）。
 * P1 只提供谓词，不改现有列表查询（接线见 docs/14 §11 P3/P4）。
 */
@Service
public class DeptScopeService {

    /** 使用单位账号与 use_unit 的绑定来源；V34 落 use_unit.openid 后由使用单位门户模块提供实现 */
    @FunctionalInterface
    public interface UseUnitBinding {
        String useUnitIdOf(Employee employee);
    }

    private static final UseUnitBinding NO_BINDING = employee -> null;

    /** manager_id 链最大深度，兼作脏数据自环的兜底 */
    private static final int MAX_CHAIN_DEPTH = 16;

    private final EmployeeMapper employeeMapper;
    private final UseUnitBinding useUnitBinding;

    /** Spring 装配：P1 尚无 use_unit 账号绑定（已完成 V34），使用单位作用域暂时返回 null */
    @Autowired
    public DeptScopeService(EmployeeMapper employeeMapper) {
        this(employeeMapper, NO_BINDING);
    }

    public DeptScopeService(EmployeeMapper employeeMapper, UseUnitBinding useUnitBinding) {
        this.employeeMapper = employeeMapper;
        this.useUnitBinding = useUnitBinding;
    }

    /** docs/14 §5 平台级：ADMIN 跨部门全权；SYS_ADMIN 保留不启用（docs/09 §3.1） */
    public boolean platform(Employee employee) {
        if (employee == null) {
            return false;
        }
        // 规范码 PLATFORM_ADMIN 与历史码 ADMIN/SYS_ADMIN 等价：只比历史码会让账号管理新建的管理员失去跨部门权限
        return RoleCatalog.isPlatformRole(employee.role);
    }

    /** docs/14 §4 部门域：存量/新建档未填 department 时按 role 回退（V31 回填之外的账号仍可判定） */
    public String departmentOf(Employee employee) {
        if (employee == null) {
            return null;
        }
        if (employee.department != null && !employee.department.isBlank()) {
            return employee.department;
        }
        return RoleCatalog.departmentOf(employee.role);
    }

    /** docs/14 §5 管理链：本人 + 直接/间接下属（沿 manager_id 逐层展开） */
    public Set<String> subordinateEmpIds(Employee employee) {
        Set<String> ids = new LinkedHashSet<>();
        if (employee == null) {
            return ids;
        }
        if (employee.id != null) {
            ids.add(employee.id);
        }
        List<String> frontier = employee.id == null ? List.of() : List.of(employee.id);
        for (int depth = 0; depth < MAX_CHAIN_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (Employee subordinate : employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>().in(Employee::getManagerId, frontier))) {
                if (subordinate.id != null && ids.add(subordinate.id)) {
                    next.add(subordinate.id);
                }
            }
            frontier = next;
        }
        return ids;
    }

    /** docs/14 §4 部门域：同部门方可互见；平台级跨部门全权 */
    public boolean sameDepartment(Employee employee, Employee target) {
        if (employee == null || target == null) {
            return false;
        }
        if (platform(employee)) {
            return true;
        }
        String department = departmentOf(employee);
        return department != null && department.equals(departmentOf(target));
    }

    /**
     * docs/14 §5 班组：本班组员工 empId（无班组时仅本人）。
     *
     * @return 可见 empId 集合；null 表示不限（平台级），沿用 EmployeeScopeService 的 null 约定
     */
    public Set<String> groupVisible(Employee employee) {
        if (employee == null) {
            return Set.of();
        }
        if (platform(employee)) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        if (employee.groupName == null || employee.groupName.isBlank()) {
            if (employee.id != null) {
                ids.add(employee.id);
            }
            return ids;
        }
        for (Employee member : employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>().eq(Employee::getGroupName, employee.groupName))) {
            if (member.id != null) {
                ids.add(member.id);
            }
        }
        return ids;
    }

    /** docs/14 §5 本人：维保人员/配合人员只看本人作业 */
    public Set<String> selfVisible(Employee employee) {
        return employee == null || employee.id == null ? Set.of() : Set.of(employee.id);
    }

    /** docs/14 §5 使用单位域：UNIT_ADMIN 只可见绑定单位；其它角色不适用本谓词，返回 null */
    public String useUnitScope(Employee employee) {
        if (employee == null || !RoleCatalog.UNIT_ADMIN.equals(employee.role)) {
            return null;
        }
        return useUnitBinding.useUnitIdOf(employee);
    }
}
