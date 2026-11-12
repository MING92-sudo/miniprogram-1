package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
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
    /** 部门级全量读：维保经理与质量负责人看整个维保部（docs/14 §5；范围由部门谓词兜底） */
    private static final Set<String> DEPT_WIDE = Set.of("MAINT_MANAGER", "MAINT_QUALITY");
    /** 不接触工单/电梯作业域的岗位：安装部三岗与维保商务（docs/14 §4 部门域互斥） */
    private static final Set<String> NO_FIELD_ROLES = Set.of(
            "INSTALL_MANAGER", "INSTALL_CLERK", "INSTALL_WORKER", "MAINT_BIZ");

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
     * 可见**读**范围的作业人员 platformId 集合（Task 8，设计文档 §8）：区域=可见范围。
     * 与 {@link #visibleWorkerPlatformIds} 的区别只有一条：本人所在区域的同区人员也计入。
     * 仅用于列表/台账这类只读投影；详情与现场作业写仍走严格谓词，避免代签到/代签退（AGENTS §3.1）。
     * 存量 region_id 为空 = 未分区，行为与改造前一致（不因加字段突然看得更多）。
     */
    public Set<String> visibleReadPlatformIds(Employee e) {
        if (PRIVILEGED.contains(e.role) || DEPT_WIDE.contains(e.role)) {
            return null;
        }
        if (NO_FIELD_ROLES.contains(e.role)) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        if (e.regionId != null && !e.regionId.isBlank()) {
            for (Employee m : employeesInRegion(e.regionId)) {
                if (m.platformId != null && !m.platformId.isBlank()) {
                    ids.add(m.platformId);
                }
            }
        }
        if ("LEADER".equals(e.role)) {
            for (Employee m : sameGroup(e)) {
                if (m.platformId != null && !m.platformId.isBlank()) {
                    ids.add(m.platformId);
                }
            }
        }
        if (ids.isEmpty() && e.platformId != null && !e.platformId.isBlank()) {
            ids.add(e.platformId);
        }
        return ids;
    }

    private List<Employee> employeesInRegion(String regionId) {
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getRegionId, regionId));
    }

    /**
     * 可见工单的作业人员 platformId 集合；privileged 返回 null 表示不限。
     * 组员/配合人员=本人；组长=本组全部员工。
     * **写权限专用**：签到/清单/签退/签字共用此谓词，不得扩到区域——否则等于代签到（AGENTS §3.1 Blocker）。
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
