package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class EmployeeScopeService {

    /** 仅维保单位管理角色不限班组； */
    private static final Set<String> PRIVILEGED = Set.of("ADMIN", "SYS_ADMIN", "PLATFORM_ADMIN");
    /** 范围由部门谓词兜底） */
    private static final Set<String> DEPT_WIDE = Set.of("MAINT_MANAGER", "MAINT_QUALITY", "MAINT_MGR");
    private static final Set<String> NO_FIELD_ROLES = Set.of(
            "INSTALL_MANAGER", "INSTALL_CLERK", "INSTALL_WORKER", "MAINT_BIZ",
            "INST_PM", "INST_CLERK", "INSTALLER", "INST_MGR");
    /** 班组读域；LEADER 只读，MAINT_SUP 保留既有写范围。 */
    private static final Set<String> TEAM_READERS = Set.of("MAINT_SUP", "LEADER");

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

    /** 与 {@link #visibleWorkerPlatformIds} 的区别只有一条：本人所在区域的同区人员也计入。 */
    public Set<String> visibleReadPlatformIds(Employee e) {
        String role = RoleCatalog.canonical(e.role);
        if (PRIVILEGED.contains(role) || DEPT_WIDE.contains(role)) {
            return null;
        }
        if (NO_FIELD_ROLES.contains(role)) {
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
        if (TEAM_READERS.contains(role)) {
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

    /** 管理端记录详情/PDF 与列表共用同一读谓词：主维保或配合人任一命中即可。 */
    public boolean canReadRecord(Employee e, MaintainRecord r) {
        Set<String> ids = visibleReadPlatformIds(e);
        if (ids == null) {
            return r != null;
        }
        return r != null
                && ((r.workerPlatformId != null && ids.contains(r.workerPlatformId))
                || (r.assistantPlatformId != null && ids.contains(r.assistantPlatformId)));
    }

    private List<Employee> employeesInRegion(String regionId) {
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getRegionId, regionId));
    }

    /** **写权限专用**：签到/清单/签退/签字共用此谓词，不得扩到区域——否则等于代签到（AGENTS §3.1 Blocker）。 */
    public Set<String> visibleWorkerPlatformIds(Employee e) {
        String role = RoleCatalog.canonical(e.role);
        if (PRIVILEGED.contains(role)) {
            return null;
        }
        if ("LEADER".equals(role)) {
            return Set.of();
        }
        if (TEAM_READERS.contains(role)) {
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
        String role = RoleCatalog.canonical(e.role);
        if (PRIVILEGED.contains(role)) {
            return null;
        }
        if (TEAM_READERS.contains(role)) {
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
