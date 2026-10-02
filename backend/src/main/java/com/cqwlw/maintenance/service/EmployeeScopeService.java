package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 班组数据权限（V7）：组长可看本组员工的工单/急修单，组员仅看本人，管理角色不限。
 * ponytail: 列表层过滤；工单详情走同一谓词，管理端独立路由不受影响。
 */
@Service
public class EmployeeScopeService {

    /** 仅维保单位管理角色不限班组；UNIT_ADMIN 属使用单位员工（经绑定产生，非人员档案），
     *  不参与班组权限也不可见维保单据列表——其确认走分享链接/本机代签（docs/01 §3.2.3） */
    private static final Set<String> PRIVILEGED = Set.of("ADMIN", "SYS_ADMIN");

    private final EmployeeMapper employeeMapper;

    public EmployeeScopeService(EmployeeMapper employeeMapper) {
        this.employeeMapper = employeeMapper;
    }

    public Employee require(String empId) {
        Employee e = empId == null || empId.isEmpty() ? null : employeeMapper.selectById(empId);
        if (e == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return e;
    }

    /**
     * 可见工单的作业人员 platformId 集合；privileged 返回 null 表示不限。
     * 组员/配合人员=本人；组长=本组全部员工。
     */
    public Set<String> visibleWorkerPlatformIds(Employee e) {
        if (PRIVILEGED.contains(e.role)) {
            return null;
        }
        if ("LEADER".equals(e.role)) {
            Set<String> ids = new HashSet<>();
            for (Employee m : sameGroup(e)) {
                if (m.platformId != null && !m.platformId.isBlank()) {
                    ids.add(m.platformId);
                }
            }
            return ids;
        }
        return e.platformId == null || e.platformId.isBlank() ? Set.of() : Set.of(e.platformId);
    }

    /** 可见急修单的登记人 empId 集合；privileged 返回 null 表示不限。 */
    public Set<String> visibleCreatorIds(Employee e) {
        if (PRIVILEGED.contains(e.role)) {
            return null;
        }
        if ("LEADER".equals(e.role)) {
            Set<String> ids = new HashSet<>();
            for (Employee m : sameGroup(e)) {
                ids.add(m.id);
            }
            return ids;
        }
        return Set.of(e.id);
    }

    private java.util.List<Employee> sameGroup(Employee e) {
        if (e.groupName == null || e.groupName.isBlank()) {
            return java.util.List.of(e);
        }
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getGroupName, e.groupName));
    }
}
