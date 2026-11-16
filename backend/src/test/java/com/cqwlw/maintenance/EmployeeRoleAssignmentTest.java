package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.PhoneMutexService;
import com.cqwlw.maintenance.service.PlatformClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人员建档的角色与部门（docs/14 §3，V31）：三部门岗位都能建号，部门由角色派生；
 * 使用单位账号不在人员目录（走 use_unit 账号），选它必须 422。
 */
class EmployeeRoleAssignmentTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);

    private AdminArchiveService service() {
        PhoneMutexService phoneMutex = mock(PhoneMutexService.class);
        when(phoneMutex.checkEmployee(any(), anyString(), anyString())).thenReturn(List.of());
        when(employeeMapper.selectCount(any())).thenReturn(0L);
        return new AdminArchiveService(mock(CompanyMapper.class), mock(UseUnitMapper.class),
                employeeMapper, mock(com.cqwlw.maintenance.mapper.SysUserRoleMapper.class),
                mock(ElevatorMapper.class), phoneMutex,
                mock(WorkOrderMapper.class), mock(PlatformClient.class));
    }

    private Employee created(String role) {
        org.mockito.Mockito.clearInvocations(employeeMapper);
        service().createEmployee(Map.of("name", "张三", "phone", "13800000088", "role", role));
        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 安装部三岗：角色码收下、部门自动落到 INSTALL、中文名取 RoleCatalog */
    @Test
    void installRolesGetDepartment() {
        Employee manager = created(RoleCatalog.INSTALL_MANAGER);
        assertEquals(RoleCatalog.INSTALL, manager.department);
        assertEquals("安装项目经理", manager.roleText);

        Employee clerk = created(RoleCatalog.INSTALL_CLERK);
        assertEquals(RoleCatalog.INSTALL, clerk.department);
        assertEquals("安装文员", clerk.roleText);
    }

    /** 维保部新岗位（商务/质量）同样可建，且部门为 MAINT */
    @Test
    void maintenanceNewRolesGetDepartment() {
        Employee biz = created(RoleCatalog.MAINT_BIZ);
        assertEquals(RoleCatalog.MAINT, biz.department);
        assertEquals("维保商务", biz.roleText);

        Employee quality = created(RoleCatalog.MAINT_QUALITY);
        assertEquals(RoleCatalog.MAINT, quality.department);
        assertEquals("维保质量负责人", quality.roleText);
    }

    /** 使用单位账号不建在人员目录：422 且不落库 */
    @Test
    void unitAdminIsNotAssignable() {
        BizException ex = assertThrows(BizException.class, () -> service().createEmployee(
                Map.of("name", "李四", "phone", "13800000089", "role", RoleCatalog.UNIT_ADMIN)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("使用单位"));
    }

    /** 未知角色同样拒绝（不能凭客户端字符串绕过） */
    @Test
    void unknownRoleRejected() {
        BizException ex = assertThrows(BizException.class, () -> service().createEmployee(
                Map.of("name", "王五", "phone", "13800000090", "role", "SUPER_USER")));
        assertEquals(422, ex.getCode());
    }

    /** 建档下拉：12 个角色里除使用单位外都可选，且带部门与中文名供前端分组 */
    @Test
    void assignableRolesCoverThreeDepartments() {
        List<RoleCatalog.Role> roles = RoleCatalog.assignableRoles();
        assertFalse(roles.isEmpty());
        assertTrue(roles.stream().noneMatch(r -> RoleCatalog.UNIT.equals(r.department())));
        for (String dept : List.of(RoleCatalog.PLATFORM, RoleCatalog.INSTALL, RoleCatalog.MAINT)) {
            assertTrue(roles.stream().anyMatch(r -> dept.equals(r.department())), "缺少部门：" + dept);
        }
        assertEquals("安装部", RoleCatalog.departmentName(RoleCatalog.INSTALL));
        assertEquals("维保部", RoleCatalog.departmentName(RoleCatalog.MAINT));
    }

    /** 改角色要同步部门，否则旧部门的数据谓词会残留 */
    @Test
    void roleChangeUpdatesDepartment() {
        AdminArchiveService svc = service();
        Employee existing = new Employee();
        existing.id = "emp_1";
        existing.role = RoleCatalog.WORKER;
        existing.department = RoleCatalog.MAINT;
        when(employeeMapper.selectById("emp_1")).thenReturn(existing);

        svc.updateEmployee("emp_1", Map.of("role", RoleCatalog.INSTALL_WORKER));
        assertEquals(RoleCatalog.INSTALL_WORKER, existing.role);
        assertEquals(RoleCatalog.INSTALL, existing.department);
        assertEquals("安装员", existing.roleText);
    }
}
