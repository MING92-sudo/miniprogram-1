package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.BizProjectMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import com.cqwlw.maintenance.mapper.SysInviteMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.service.AuditLogService;
import com.cqwlw.maintenance.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgMembersTest {

    private final SysOrgMapper orgMapper = mock(SysOrgMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final RegionMapper regionMapper = mock(RegionMapper.class);
    private final LedgerService service = new LedgerService(orgMapper, mock(BizProjectMapper.class),
            mock(SysInviteMapper.class), mock(BizElevatorChangeMapper.class), mock(ElevatorMapper.class),
            employeeMapper, regionMapper, mock(AuditLogService.class));

    private SysOrg org(String id, String type, String name) {
        SysOrg o = new SysOrg();
        o.id = id;
        o.type = type;
        o.name = name;
        return o;
    }

    private Employee emp(String id, String name, String department, String groupName) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.department = department;
        e.groupName = groupName;
        e.enabled = true;
        return e;
    }

    @Test
    void teamNodeReturnsOnlyItsMembers() {
        SysOrg team = org("org_t1", "TEAM", "维保一组");
        when(orgMapper.selectById("org_t1")).thenReturn(team);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                emp("emp_a", "张三", "MAINT", "维保一组"),
                emp("emp_b", "李四", "MAINT", "维保二组")));

        List<Map<String, Object>> out = service.orgMembers("org_t1");

        assertEquals(List.of("emp_a"), out.stream().map(m -> m.get("id")).toList());
        Map<String, Object> row = out.get(0);
        assertEquals("张三", row.get("name"));
        assertEquals("维保一组", row.get("groupName"));
        assertTrue(row.containsKey("regionName"), "行投影须含 regionName 键");
        assertEquals(Boolean.TRUE, row.get("enabled"));
    }

    @Test
    void deptNodeReturnsDepartmentMembers() {
        SysOrg dept = org("org_d1", "DEPT", "维保部");
        when(orgMapper.selectById("org_d1")).thenReturn(dept);
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                emp("emp_a", "张三", "MAINT", "维保一组"),
                emp("emp_b", "李四", "INSTALL", "安装一组")));

        List<Map<String, Object>> out = service.orgMembers("org_d1");

        assertEquals(List.of("emp_a"), out.stream().map(m -> m.get("id")).toList());
    }

    @Test
    void unitNodeReturnsEmpty() {
        SysOrg unit = org("org_u1", "UNIT", "某物业公司");
        when(orgMapper.selectById("org_u1")).thenReturn(unit);

        List<Map<String, Object>> out = service.orgMembers("org_u1");

        assertTrue(out.isEmpty(), "UNIT（使用单位）节点无内部成员");
    }

    @Test
    void unknownOrgId404() {
        when(orgMapper.selectById("nope")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.orgMembers("nope"));

        assertEquals(1404, e.getCode());
    }
}
