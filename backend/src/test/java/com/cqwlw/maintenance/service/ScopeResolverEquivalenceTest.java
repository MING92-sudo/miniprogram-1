package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysRoleScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ScopeResolver 与 V7 谓词逐集合相等（spec §4.5）；读写不对称保留；写绝不扩区域/部门。 */
class ScopeResolverEquivalenceTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final EmployeeScopeService v7 = new EmployeeScopeService(employeeMapper);
    private final DeptScopeService dept = new DeptScopeService(employeeMapper);
    private final ScopeResolver resolver =
            new ScopeResolver(v7, dept, mock(SysRoleScopeMapper.class));

    private Employee emp(String id, String role, String platformId, String regionId, String groupName) {
        Employee e = new Employee();
        e.id = id; e.role = role; e.platformId = platformId; e.regionId = regionId; e.groupName = groupName;
        return e;
    }

    @Test
    void readWriteEquivalenceForAllLegacyEmployeeRoles() {
        // 区域 r1 含 p1/p2；班组 g1 含 p1/p3
        Employee r1m1 = emp("e1", "WORKER", "p1", "r1", "g1");
        Employee r1m2 = emp("e2", "WORKER", "p2", "r1", "g2");
        Employee g1m3 = emp("e3", "WORKER", "p3", "r2", "g1");
        when(employeeMapper.selectList(any())).thenReturn(List.of(r1m1, r1m2, g1m3));

        String[] roles = {"ADMIN", "SYS_ADMIN", "MAINT_MANAGER", "MAINT_QUALITY", "LEADER", "WORKER",
                "ASSISTANT", "INSTALL_MANAGER", "INSTALL_CLERK", "INSTALL_WORKER", "MAINT_BIZ"};
        for (String role : roles) {
            Employee e = emp("self", role, "p_self", "r1", "g1");
            assertEquals(v7.visibleReadPlatformIds(e), resolver.readPlatformIds(e), "读不等价: " + role);
            assertEquals(v7.visibleWorkerPlatformIds(e), resolver.writePlatformIds(e), "写不等价: " + role);
        }
    }

    @Test
    void writeNeverExpandsToRegionOrDepartment() {
        Employee r1m1 = emp("e1", "WORKER", "p1", "r1", "g1");
        Employee r1m2 = emp("e2", "WORKER", "p2", "r1", "g2");
        when(employeeMapper.selectList(any())).thenReturn(List.of(r1m1, r1m2));
        // MAINT_MANAGER 读=null（部门全量），写=本人（绝不 null）
        Employee mm = emp("mm", "MAINT_MANAGER", "p_mm", "r1", "g1");
        assertEquals(null, resolver.readPlatformIds(mm));
        assertEquals(Set.of("p_mm"), resolver.writePlatformIds(mm));
        // WORKER 读含区域 p1/p2/p_self，写仅本人
        Employee w = emp("w", "WORKER", "p_self", "r1", "g1");
        assertEquals(Set.of("p_self"), resolver.writePlatformIds(w));
    }

    @Test
    void portalUnitScopeConvergesToBoundUnit() {
        Employee unit = emp("u1", "UNIT_ADMIN", null, null, null);
        // DeptScopeService 默认 NO_BINDING→null；等价靶即 useUnitScope 输出
        assertEquals(dept.useUnitScope(unit), resolver.unitScope(unit));
    }
}