package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 部门域谓词（docs/14 §4 部门互斥、§5 部门内可见范围）：不起 Spring 容器，
 * EmployeeMapper 按查询参数模拟 manager_id / group_name 过滤。
 */
class DeptScopeServiceTest {

    /** 组织树：维保经理 m1 → 班组长 l1 → 维保人员 w1/w2；m1 另带 w3（B 组）；安装经理 i1 独立部门 */
    private final List<Employee> roster = List.of(
            employee("m1", RoleCatalog.MAINT_MANAGER, null, "MAINT", null),
            employee("l1", RoleCatalog.LEADER, "A", "MAINT", "m1"),
            employee("w1", RoleCatalog.WORKER, "A", "MAINT", "l1"),
            employee("w2", RoleCatalog.WORKER, "A", "MAINT", "l1"),
            employee("w3", RoleCatalog.WORKER, "B", "MAINT", "m1"),
            employee("i1", RoleCatalog.INSTALL_MANAGER, null, "INSTALL", null));

    /** LambdaQueryWrapper 要把 Employee::getManagerId 解析成列名需要 TableInfo；单测不起 Spring/MyBatis 容器 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Employee.class);
    }

    private static Employee employee(String id, String role, String group, String department, String managerId) {
        Employee e = new Employee();
        e.id = id;
        e.role = role;
        e.groupName = group;
        e.department = department;
        e.managerId = managerId;
        return e;
    }

    private Employee byId(String id) {
        return roster.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
    }

    private DeptScopeService service(EmployeeMapper mapper) {
        return new DeptScopeService(mapper, employee -> "uu_1");
    }

    /** 按 wrapper 渲染出的 SQL 与参数模拟库表过滤：manager_id IN / group_name = */
    private EmployeeMapper mapper() {
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Employee> wrapper = invocation.getArgument(0);
            String sql = wrapper.getSqlSegment(); // 触发 MP 填充 paramNameValuePairs（与真实 SQL 渲染同序）
            Collection<?> params = wrapper.getParamNameValuePairs().values();
            boolean byManager = sql.contains("manager_id");
            List<Employee> hits = new ArrayList<>();
            for (Employee e : roster) {
                // MP 把 IN 的每个元素都展开成单个参数，故按字符串逐值比较
                boolean match = params.stream().anyMatch(p -> p instanceof String value
                        && value.equals(byManager ? e.managerId : e.groupName));
                if (match) {
                    hits.add(e);
                }
            }
            return hits;
        });
        return mapper;
    }

    private DeptScopeService service() {
        return service(mapper());
    }

    /** docs/14 §5 平台级 + §4 部门互斥 */
    @Test
    void platformAndDepartmentIsolation() {
        DeptScopeService scope = service();
        assertTrue(scope.platform(employee("a1", RoleCatalog.ADMIN, null, "PLATFORM", null)));
        assertTrue(scope.platform(employee("s1", RoleCatalog.SYS_ADMIN, null, "PLATFORM", null)));
        assertFalse(scope.platform(byId("m1")));
        assertFalse(scope.platform(null));

        assertTrue(scope.sameDepartment(byId("m1"), byId("w2")), "同部门（维保部）可见");
        assertFalse(scope.sameDepartment(byId("l1"), byId("i1")), "安装部与维保部平级互斥");
        assertTrue(scope.sameDepartment(employee("a1", RoleCatalog.ADMIN, null, "PLATFORM", null), byId("i1")),
                "平台级跨部门全权");
        assertFalse(scope.sameDepartment(byId("w1"), null));
        assertFalse(scope.sameDepartment(null, byId("w1")));
    }

    /** 存量/新建档未填 department 时按角色回退，避免 V31 回填之外的账号失去部门口径 */
    @Test
    void departmentFallsBackToRole() {
        DeptScopeService scope = service();
        assertEquals("MAINT", scope.departmentOf(employee("x1", RoleCatalog.WORKER, "A", null, null)));
        assertEquals("INSTALL", scope.departmentOf(employee("x2", RoleCatalog.INSTALL_CLERK, null, null, null)));
        assertEquals("UNIT", scope.departmentOf(employee("x3", RoleCatalog.UNIT_ADMIN, null, null, null)));
        assertEquals("MAINT", scope.departmentOf(byId("l1")));
        assertNull(scope.departmentOf(null));
        assertNull(scope.departmentOf(employee("x4", "HACKER", null, null, null)));
    }

    /** docs/14 §5 管理链：经理 → 班组长 → 维保人员（本人 + 直接/间接下属） */
    @Test
    void managerChainIncludesSelfAndIndirectSubordinates() {
        DeptScopeService scope = service();
        assertEquals(Set.of("m1", "l1", "w1", "w2", "w3"), scope.subordinateEmpIds(byId("m1")));
        assertEquals(Set.of("l1", "w1", "w2"), scope.subordinateEmpIds(byId("l1")));
        assertEquals(Set.of("w1"), scope.subordinateEmpIds(byId("w1")));
        assertTrue(scope.subordinateEmpIds(null).isEmpty());
    }

    /** 脏数据自环不应死循环（manager_id 指向本人） */
    @Test
    void managerChainStopsOnCycle() {
        Employee self = employee("self", RoleCatalog.MAINT_MANAGER, null, "MAINT", "self");
        EmployeeMapper mapper = mock(EmployeeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(self));
        assertEquals(Set.of("self"), new DeptScopeService(mapper).subordinateEmpIds(self));
    }

    /** docs/14 §5 班组：本班组员工；无班组仅本人；平台级不限（null） */
    @Test
    void groupAndSelfVisibility() {
        DeptScopeService scope = service();
        assertEquals(Set.of("l1", "w1", "w2"), scope.groupVisible(byId("l1")));
        assertEquals(Set.of("w3"), scope.groupVisible(byId("w3")));
        assertEquals(Set.of("x1"), scope.groupVisible(employee("x1", RoleCatalog.WORKER, null, "MAINT", null)),
                "无班组时只能看本人");
        assertNull(scope.groupVisible(employee("a1", RoleCatalog.ADMIN, null, "PLATFORM", null)),
                "平台级不限班组（null 与 EmployeeScopeService 约定一致）");
        assertTrue(scope.groupVisible(null).isEmpty());

        assertEquals(Set.of("w1"), scope.selfVisible(byId("w1")));
        assertTrue(scope.selfVisible(null).isEmpty());
    }

    /** docs/14 §5 使用单位域：UNIT_ADMIN 只可见绑定单位，其它角色不适用 */
    @Test
    void useUnitScopeOnlyAppliesToUnitAdmin() {
        DeptScopeService scope = service();
        assertEquals("uu_1", scope.useUnitScope(employee("u1", RoleCatalog.UNIT_ADMIN, null, "UNIT", null)));
        assertNull(scope.useUnitScope(byId("m1")));
        assertNull(scope.useUnitScope(null));
        assertNull(new DeptScopeService(mapper()).useUnitScope(
                        employee("u2", RoleCatalog.UNIT_ADMIN, null, "UNIT", null)),
                "未接入 use_unit 账号绑定前返回 null（V30 在飞）");
    }
}
