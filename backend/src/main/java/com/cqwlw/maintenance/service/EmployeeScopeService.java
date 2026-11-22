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

/**
 * 班组数据权限（V7）：组长可看本组员工的工单/急修单，组员仅看本人，管理角色不限。
 * ponytail: 列表层过滤；工单详情走同一谓词，管理端独立路由不受影响。
 * C-26 规范码归一（2026-11-20，docs/12 C-26 读域部分先行）：判定前统一经
 * {@link RoleCatalog#canonical} 归一——规范码主角色（MAINT_MGR/MAINT_SUP/INST_* 等）
 * 不再落空回退本人兜底（此前 MAINT_MGR/MAINT_SUP 读列表恒空，属真实缺陷）；
 * 旧码集合保留兼容，语义逐条等价（LEADER≡MAINT_SUP 班组读、写谓词不扩区域/部门不变）。
 */
@Service
public class EmployeeScopeService {

    /** 仅维保单位管理角色不限班组；UNIT_ADMIN 属使用单位员工（经绑定产生，非人员档案），
     *  不参与班组权限也不可见维保单据列表——其确认走分享链接/本机代签（docs/01 §3.2.3） */
    private static final Set<String> PRIVILEGED = Set.of("ADMIN", "SYS_ADMIN", "PLATFORM_ADMIN");
    /** 部门级全量读：维保经理与质量负责人看整个维保部（docs/14 §5；范围由部门谓词兜底） */
    private static final Set<String> DEPT_WIDE = Set.of("MAINT_MANAGER", "MAINT_QUALITY", "MAINT_MGR");
    /** 不接触工单/电梯作业域的岗位：安装部各岗与维保商务（docs/14 §4 部门域互斥） */
    private static final Set<String> NO_FIELD_ROLES = Set.of(
            "INSTALL_MANAGER", "INSTALL_CLERK", "INSTALL_WORKER", "MAINT_BIZ",
            "INST_PM", "INST_CLERK", "INSTALLER", "INST_MGR");
    /** 班组长读域（LEADER 的规范码等价）：本班组全部员工 */
    private static final String TEAM_LEAD = "MAINT_SUP";

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
        if (TEAM_LEAD.equals(role)) {
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

    /**
     * 可见工单的作业人员 platformId 集合；privileged 返回 null 表示不限。
     * 组员/配合人员=本人；组长=本组全部员工。
     * **写权限专用**：签到/清单/签退/签字共用此谓词，不得扩到区域——否则等于代签到（AGENTS §3.1 Blocker）。
     */
    public Set<String> visibleWorkerPlatformIds(Employee e) {
        String role = RoleCatalog.canonical(e.role);
        if (PRIVILEGED.contains(role)) {
            return null;
        }
        if (TEAM_LEAD.equals(role)) {
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
        if (TEAM_LEAD.equals(role)) {
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
