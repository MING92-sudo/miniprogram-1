package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.PhoneMutexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 手机号互斥预校验（docs/01 §3.2.4 + docs/05 §2.3）：
 * 互斥范围仅五个角色，emergencyPhone/recorderPhone 不参与；
 * 同一手机号跨 ≥2 个不同角色 → 1002 + conflicts[]；同角色同人不算冲突。
 */
class PhoneMutexServiceTest {

    private CompanyMapper companyMapper;
    private UseUnitMapper useUnitMapper;
    private EmployeeMapper employeeMapper;
    private PhoneMutexService service;

    @BeforeEach
    void setUp() {
        companyMapper = mock(CompanyMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        employeeMapper = mock(EmployeeMapper.class);

        Company c = new Company();
        c.id = "co_1";
        c.name = "维保单位";
        c.workMenegerPhone = "13700000001";
        when(companyMapper.selectList(any())).thenReturn(List.of(c));

        Employee worker = new Employee();
        worker.id = "emp_1";
        worker.name = "张伟";
        worker.role = "WORKER";
        worker.phone = "13800000001";
        Employee unitAdmin = new Employee();
        unitAdmin.id = "unit_1";
        unitAdmin.name = "王芳";
        unitAdmin.role = "UNIT_ADMIN";
        unitAdmin.phone = "13800000003";
        Employee admin = new Employee();
        admin.id = "emp_admin";
        admin.name = "郑浩";
        admin.role = "ADMIN";
        admin.phone = "13800000009";
        when(employeeMapper.selectList(any())).thenReturn(List.of(worker, unitAdmin, admin));

        UseUnit other = new UseUnit();
        other.id = "uu_2";
        other.unitName = "其他物业";
        other.unitPrincipalPhone = "13930003002";
        other.elevatorAdministerPhone = "13800003005";
        other.emergencyPhone = "13800000001"; // 应急电话与维保人员同号：不参与互斥
        when(useUnitMapper.selectList(any())).thenReturn(List.of(other));

        service = new PhoneMutexService(companyMapper, useUnitMapper, employeeMapper);
    }

    @Test
    void sameRoleSamePhoneIsNotConflict() {
        // 员工表中的安全管理员与使用单位档案的安全管理员是同一人：同角色不冲突
        List<PhoneMutexService.Party> parties = List.of(
                new PhoneMutexService.Party("使用单位安全管理员", "王芳", "13800000003"),
                new PhoneMutexService.Party("使用单位安全管理员", "世纪物业", "13800000003"));
        assertTrue(PhoneMutexService.findConflicts(parties).isEmpty());
    }

    @Test
    void crossRoleSamePhoneIsConflict() {
        List<PhoneMutexService.Party> parties = List.of(
                new PhoneMutexService.Party("使用单位负责人", "物业", "13910001001"),
                new PhoneMutexService.Party("使用单位安全管理员", "物业", "13910001001"),
                new PhoneMutexService.Party("维保人员", "张伟", "13800000001"));
        List<Map<String, Object>> conflicts = PhoneMutexService.findConflicts(parties);
        assertEquals(1, conflicts.size());
        assertEquals("13910001001", conflicts.get(0).get("phone"));
        assertEquals(2, ((List<?>) conflicts.get(0).get("roles")).size());
    }

    @Test
    void blankPhonesAreIgnored() {
        List<PhoneMutexService.Party> parties = List.of(
                new PhoneMutexService.Party("维保人员", "张伟", ""),
                new PhoneMutexService.Party("维保人员", "李强", null));
        assertTrue(PhoneMutexService.findConflicts(parties).isEmpty());
    }

    @Test
    void useUnitSaveFlagsWorkerAndPrincipalCollisions() {
        // 新使用单位：负责人与其他单位负责人同号 → 同角色不算冲突（视为同一人）；
        // 安全管理员填了维保人员手机 → 跨角色冲突 1 组
        List<Map<String, Object>> conflicts = service.checkUseUnit(
                "uu_new", "13930003002", "13800000001");
        assertEquals(1, conflicts.size());
        assertEquals("13800000001", conflicts.get(0).get("phone"));
    }

    @Test
    void emergencyPhoneDoesNotParticipate() {
        // 应急电话与维保人员同号：按 docs/01 §3.2.4 不参与互斥 → 通过
        UseUnit u = new UseUnit();
        u.id = "uu_new";
        u.unitName = "新物业";
        List<Map<String, Object>> conflicts = service.checkUseUnit(
                u.id, "13900009999", "13900009998");
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void employeeMutexSkipsAdminRoles() {
        // ADMIN 账号不对应 2.6 角色：手机号与任何档案同号都不冲突
        assertTrue(service.checkEmployee(null, "ADMIN", "13930003002").isEmpty());
        // 维保人员手机与既有使用单位负责人同号 → 冲突
        assertEquals(1, service.checkEmployee(null, "WORKER", "13930003002").size());
    }
}
