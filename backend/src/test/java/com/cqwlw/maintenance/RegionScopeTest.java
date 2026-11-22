package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegionScopeTest {

    private static Employee emp(String id, String role, String regionId, String groupName, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = id;
        e.role = role;
        e.regionId = regionId;
        e.groupName = groupName;
        e.platformId = platformId;
        return e;
    }

    private static EmployeeScopeService service(List<Employee> regionMembers, List<Employee> groupMembers) {
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        // 第 1 次 selectList = 区域成员；第 2 次（组长）= 班组成员
        when(mapper.selectList(any())).thenReturn(regionMembers, groupMembers);
        return new EmployeeScopeService(mapper);
    }

    /** 维保人员：只看到本区域电梯（同区同事名下的也算），看不到外区域 */
    @Test
    void workerSeesOnlyOwnRegionElevators() {
        Employee me = emp("emp_a", "WORKER", "r1", "g1", "wp_a");
        Employee peer = emp("emp_b", "WORKER", "r1", "g2", "wp_b");
        EmployeeScopeService svc = service(List.of(me, peer), List.of());

        Set<String> ids = svc.visibleReadPlatformIds(me);
        assertEquals(Set.of("wp_a", "wp_b"), ids);
        assertFalse(ids.contains("wp_outside"));
    }

    /** 组长：区域 ∪ 本班组（跨区组员也能看到） */
    @Test
    void leaderSeesRegionPlusGroup() {
        Employee leader = emp("emp_l", "LEADER", "r1", "g1", "wp_l");
        Employee regionPeer = emp("emp_r", "WORKER", "r1", "g9", "wp_r");
        Employee groupPeer = emp("emp_g", "WORKER", "r2", "g1", "wp_g");
        EmployeeScopeService svc = service(List.of(leader, regionPeer), List.of(leader, groupPeer));

        assertEquals(Set.of("wp_l", "wp_r", "wp_g"), svc.visibleReadPlatformIds(leader));
    }

    /** 未分区（region_id 为空 = 存量数据）：一律按原口径，只看本人 */
    @Test
    void unassignedRegionFallsBackToSelf() {
        Employee me = emp("emp_a", "WORKER", null, "g1", "wp_a");
        EmployeeScopeService svc = service(List.of(), List.of());
        assertEquals(Set.of("wp_a"), svc.visibleReadPlatformIds(me));
    }

    /** 安装部/商务不接触工单电梯域：空集合（不是 null，null 表示不限） */
    @Test
    void nonFieldRolesSeeNothing() {
        Employee biz = emp("emp_b", "MAINT_BIZ", "r1", null, "wp_b");
        Employee install = emp("emp_i", "INSTALL_CLERK", "r1", null, "wp_i");
        EmployeeScopeService svc = service(List.of(), List.of());
        assertEquals(Set.of(), svc.visibleReadPlatformIds(biz));
        assertEquals(Set.of(), svc.visibleReadPlatformIds(install));
    }

    /** 管理角色不限（null），维保经理/质量负责人看整个维保部 */
    @Test
    void managersAreUnrestricted() {
        EmployeeScopeService svc = service(List.of(), List.of());
        assertNull(svc.visibleReadPlatformIds(emp("a", "ADMIN", null, null, "wp_a")));
        assertNull(svc.visibleReadPlatformIds(emp("m", "MAINT_MANAGER", "r1", null, "wp_m")));
        assertNull(svc.visibleReadPlatformIds(emp("q", "MAINT_QUALITY", "r1", null, "wp_q")));
    }

    @Test
    void writeScopeStaysStrictEvenInSameRegion() {
        Employee me = emp("emp_a", "WORKER", "r1", "g1", "wp_a");
        Employee peer = emp("emp_b", "WORKER", "r1", "g1", "wp_b");
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(me, peer));
        EmployeeScopeService svc = new EmployeeScopeService(mapper);

        assertEquals(Set.of("wp_a"), svc.visibleWorkerPlatformIds(me));
        assertTrue(svc.visibleReadPlatformIds(me).contains("wp_b"));
    }

    /** region 为空的存量员工：写谓词行为不变（本人/本组） */
    @Test
    void writeScopeUnchangedForLegacyData() {
        Employee leader = emp("emp_l", "LEADER", null, "g1", "wp_l");
        Employee member = emp("emp_m", "WORKER", null, "g1", "wp_m");
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(leader, member));
        EmployeeScopeService svc = new EmployeeScopeService(mapper);
        assertEquals(Set.of("wp_l", "wp_m"), svc.visibleWorkerPlatformIds(leader));
    }

    /** require() 对无效 empId 仍抛 401（回归） */
    @Test
    void requireRejectsUnknownEmployee() {
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        when(mapper.selectById("nobody")).thenReturn(null);
        EmployeeScopeService svc = new EmployeeScopeService(mapper);
        BizException ex = assertThrows(BizException.class, () -> svc.require("nobody"));
        assertEquals(401, ex.getCode());
    }

    @Test
    void canonicalMaintMgrGetsDeptWideRead() {
        Employee mgr = emp("emp_m", "MAINT_MGR", null, null, "wp_m");
        EmployeeScopeService svc = service(List.of(), List.of());
        assertNull(svc.visibleReadPlatformIds(mgr));
    }

    /** MAINT_SUP=旧码 LEADER 同口径——本班组读+写谓词，不再本人兜底 */
    @Test
    void canonicalMaintSupGetsTeamScope() {
        Employee sup = emp("emp_s", "MAINT_SUP", null, "g1", "wp_s");
        Employee mate = emp("emp_t", "MAINT_TECH", null, "g1", "wp_t");
        EmployeeScopeService svc = service(List.of(sup, mate), List.of(sup, mate));
        assertEquals(Set.of("wp_s", "wp_t"), svc.visibleReadPlatformIds(sup));
        assertEquals(Set.of("wp_s", "wp_t"), svc.visibleWorkerPlatformIds(sup));
    }

    @Test
    void canonicalPlatformAdminUnrestricted() {
        Employee pa = emp("emp_p", "PLATFORM_ADMIN", null, null, null);
        EmployeeScopeService svc = service(List.of(), List.of());
        assertNull(svc.visibleReadPlatformIds(pa));
        assertNull(svc.visibleWorkerPlatformIds(pa));
        assertNull(svc.visibleCreatorIds(pa));
    }

    @Test
    void canonicalInstallRolesNoFieldAccess() {
        EmployeeScopeService svc = service(List.of(), List.of());
        assertTrue(svc.visibleReadPlatformIds(emp("emp_pm", "INST_PM", null, null, "wp_pm")).isEmpty());
        assertTrue(svc.visibleReadPlatformIds(emp("emp_iw", "INSTALLER", null, null, "wp_iw")).isEmpty());
        assertTrue(svc.visibleReadPlatformIds(emp("emp_im", "INST_MGR", null, null, "wp_im")).isEmpty());
    }
}
