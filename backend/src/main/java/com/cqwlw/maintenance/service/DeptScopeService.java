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

@Service
public class DeptScopeService {

    @FunctionalInterface
    public interface UseUnitBinding {
        String useUnitIdOf(Employee employee);
    }

    private static final UseUnitBinding NO_BINDING = employee -> null;

    /** manager_id 链最大深度，兼作脏数据自环的兜底 */
    private static final int MAX_CHAIN_DEPTH = 16;

    private final EmployeeMapper employeeMapper;
    private final UseUnitBinding useUnitBinding;

    @Autowired
    public DeptScopeService(EmployeeMapper employeeMapper) {
        this(employeeMapper, NO_BINDING);
    }

    public DeptScopeService(EmployeeMapper employeeMapper, UseUnitBinding useUnitBinding) {
        this.employeeMapper = employeeMapper;
        this.useUnitBinding = useUnitBinding;
    }

    public boolean platform(Employee employee) {
        if (employee == null) {
            return false;
        }
        // 规范码 PLATFORM_ADMIN 与历史码 ADMIN/SYS_ADMIN 等价：只比历史码会让账号管理新建的管理员失去跨部门权限
        return RoleCatalog.isPlatformRole(employee.role);
    }

    public String departmentOf(Employee employee) {
        if (employee == null) {
            return null;
        }
        if (employee.department != null && !employee.department.isBlank()) {
            return employee.department;
        }
        return RoleCatalog.departmentOf(employee.role);
    }

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

    public Set<String> selfVisible(Employee employee) {
        return employee == null || employee.id == null ? Set.of() : Set.of(employee.id);
    }

    public String useUnitScope(Employee employee) {
        if (employee == null || !RoleCatalog.UNIT_ADMIN.equals(employee.role)) {
            return null;
        }
        return useUnitBinding.useUnitIdOf(employee);
    }
}
