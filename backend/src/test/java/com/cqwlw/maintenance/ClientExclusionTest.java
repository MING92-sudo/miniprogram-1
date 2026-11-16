package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MiniappMenuService;
import com.cqwlw.maintenance.controller.UserController;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.BizExportApprovalMapper;
import com.cqwlw.maintenance.mapper.BizProjectMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.SysInviteMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.AuditLogService;
import com.cqwlw.maintenance.service.DataDomainService;
import com.cqwlw.maintenance.service.LedgerService;
import com.cqwlw.maintenance.service.PhoneMutexService;
import com.cqwlw.maintenance.service.PlatformClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 T5：员工/组织/统计内部视图临时排除客户主体 + 菜单 badges 本单位收敛（docs/14）。
 * ① GET /employees 人员目录与 GET /users 账号视图不含客户主体行——生效角色集（主角色 ∪ sys_user_role）
 *   任一内部（principalTypeOf==EMPLOYEE）即保留、全 CUSTOMER 排除；判定不用 department 字面量
 *   （客户 department 存 UNIT/null 两代值，字面量会漏判一半）；
 * ② GET /org 内部组织树不渲染 UNIT 节点（数据保留，P3 组织树重建）；
 * ③ GET /stats 平台级全量口径、无人数统计键；客户令牌已被 T3 业务路由白名单拒绝
 *   （AuthInterceptorV2Test 锁定 /users /org /stats /employees 403），内部全量不变；
 * ④ CLIENT badges.pendingSigns 修复跨单位数量泄漏：按本单位锚点（sys_user_role.org_id，
 *   与 UnitPortalController.unitId 同口径）+ T4 可签集合（lifecycleStatus=PENDING_CLIENT_SIGNATURE
 *   + confirmStatus=PENDING + 链末端最新版本）计数，不再计 PENDING_CLIENT_SIGN（双签未齐），
 *   无锚点 fail-closed 计 0、绝不回退全表 count。
 */
class ClientExclusionTest {

    // ── fixtures ──

    private Employee emp(String id, String name, String role) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = role;
        e.enabled = true;
        return e;
    }

    private SysUserRole ur(String userId, String roleCode, String orgId) {
        SysUserRole r = new SysUserRole();
        r.id = "ur_" + userId + "_" + roleCode;
        r.userId = userId;
        r.userType = "EMPLOYEE";
        r.roleCode = roleCode;
        r.orgId = orgId;
        return r;
    }

    private SysOrg org(String id, String type) {
        SysOrg o = new SysOrg();
        o.id = id;
        o.type = type;
        o.name = id;
        return o;
    }

    private MaintainRecord rec(String id, String elevatorCode, String lifecycleStatus, String confirmStatus) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = elevatorCode;
        r.lifecycleStatus = lifecycleStatus;
        r.confirmStatus = confirmStatus;
        return r;
    }

    private AdminArchiveService archiveService(EmployeeMapper employees, SysUserRoleMapper userRoles) {
        return new AdminArchiveService(mock(CompanyMapper.class), mock(UseUnitMapper.class),
                employees, userRoles, mock(ElevatorMapper.class),
                mock(PhoneMutexService.class), mock(WorkOrderMapper.class), mock(PlatformClient.class));
    }

    // ── ① 员工目录 / 账号视图排除客户主体行 ──

    /** brief Step 1：employeeList 不含 CLIENT_* 行；UNIT_ADMIN 旧码与全客户多角色同样排除；任一内部角色即保留 */
    @Test
    void employeeListExcludesCustomerPrincipalRows() {
        EmployeeMapper employees = mock(EmployeeMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        Employee tech = emp("emp_1", "张伟", "MAINT_TECH");        // 内部 → 保留
        Employee client = emp("emp_zw", "王芳", "CLIENT_ADMIN");    // 客户 → 排除
        Employee legacy = emp("emp_u", "旧门户", "UNIT_ADMIN");     // 旧码 ALIAS 归一 CUSTOMER → 排除
        Employee onlyRoles = emp("emp_n", "多角色客户", null);       // 主角色缺失、多角色集全客户 → 排除
        Employee mixed = emp("emp_m", "兼任", "WORKER");            // 角色集含内部（WORKER→EMPLOYEE）→ 保留（规则锁定，R7 正常不产生此数据）
        when(employees.selectList(any())).thenReturn(List.of(tech, client, legacy, onlyRoles, mixed));
        when(userRoles.selectList(any())).thenReturn(
                List.of(),                                          // emp_1
                List.of(ur("emp_zw", "CLIENT_ADMIN", "uu_1")),      // emp_zw
                List.of(),                                          // emp_u
                List.of(ur("emp_n", "CLIENT_SIGNER", "uu_1")),      // emp_n
                List.of(ur("emp_m", "CLIENT_ADMIN", "uu_1")));      // emp_m

        List<Map<String, Object>> out = archiveService(employees, userRoles).employeeList();

        assertTrue(out.stream().noneMatch(m -> String.valueOf(m.get("role")).startsWith("CLIENT")),
                "人员目录不得出现 CLIENT_* 行");
        assertEquals(List.of("emp_1", "emp_m"), out.stream().map(m -> m.get("id")).toList(),
                "UNIT_ADMIN 旧码 / 主角色缺失的全客户账号同样排除，仅内部主体保留");
    }

    /** brief Step 1：GET /users 账号视图不含 CLIENT_* 行（含 UNIT_ADMIN 旧码账号） */
    @Test
    void usersListExcludesCustomerPrincipalRows() {
        EmployeeMapper employees = mock(EmployeeMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        Employee tech = emp("emp_1", "张伟", "MAINT_TECH");
        Employee client = emp("emp_zw", "王芳", "CLIENT_ADMIN");
        Employee legacy = emp("emp_u", "旧门户", "UNIT_ADMIN");
        when(employees.selectList(any())).thenReturn(List.of(tech, client, legacy));
        when(userRoles.selectList(any())).thenReturn(
                List.of(ur("emp_1", "MAINT_TECH", null)),
                List.of(ur("emp_zw", "CLIENT_ADMIN", "uu_1"), ur("emp_zw", "CLIENT_SIGNER", "uu_1")),
                List.of());
        UserController controller = new UserController(employees, userRoles, mock(UseUnitMapper.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(Set.of("SYS_ADMIN"));

        List<Map<String, Object>> out = controller.list(req).getData();

        assertTrue(out.stream().noneMatch(m -> ((List<?>) m.get("roles")).stream()
                .anyMatch(r -> String.valueOf(r).startsWith("CLIENT"))), "账号视图不得出现 CLIENT 角色行");
        assertEquals(List.of("emp_1"), out.stream().map(m -> m.get("id")).toList());
    }

    // ── ② 组织树不渲染 UNIT 节点 ──

    @Test
    void orgTreeExcludesUnitNodes() {
        SysOrgMapper orgMapper = mock(SysOrgMapper.class);
        when(orgMapper.selectList(any())).thenReturn(List.of(
                org("org_c", "COMPANY"), org("org_d", "DEPT"), org("org_t", "TEAM"), org("org_u", "UNIT")));
        LedgerService service = new LedgerService(orgMapper, mock(BizProjectMapper.class),
                mock(SysInviteMapper.class), mock(BizElevatorChangeMapper.class),
                mock(ElevatorMapper.class), mock(AuditLogService.class));

        List<SysOrg> out = service.orgTree();

        assertTrue(out.stream().noneMatch(o -> "UNIT".equals(o.type)), "内部组织树不得渲染 UNIT 节点");
        assertEquals(List.of("org_c", "org_d", "org_t"), out.stream().map(o -> o.id).toList());
    }

    // ── ③ 统计口径（无人数键，内部全量保持；客户令牌不可达由 T3 锁定） ──

    @Test
    void statsKeepsInternalTotalsAndHasNoHeadcount() {
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        WorkOrderMapper orders = mock(WorkOrderMapper.class);
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        when(elevators.selectCount(null)).thenReturn(5L);
        when(orders.selectCount(null)).thenReturn(6L);
        when(records.selectCount(null)).thenReturn(7L);
        DataDomainService service = new DataDomainService(mock(SysConfigMapper.class),
                mock(BizExportApprovalMapper.class), mock(SysAuditLogMapper.class),
                elevators, orders, records, mock(AuditLogService.class));

        Map<String, Object> stats = service.stats();

        assertEquals(5L, stats.get("elevators"));
        assertEquals(6L, stats.get("workOrders"));
        assertEquals(7L, stats.get("records"));
        assertTrue(stats.keySet().stream().noneMatch(k -> k.toLowerCase().contains("employee")
                        || k.toLowerCase().contains("user") || k.toLowerCase().contains("people")),
                "stats 不含人数统计键；客户令牌 GET /stats 已被 T3 白名单 403（AuthInterceptorV2Test）");
    }

    // ── ④ CLIENT badges：本单位 + T4 可签口径 ──

    /** brief Step 1：CLIENT_ADMIN(uu_1) pendingSigns 只数本单位可签，不含 uu_2；不计 PENDING_CLIENT_SIGN、不计旧版本 */
    @Test
    @SuppressWarnings("unchecked")
    void clientBadgeCountsOwnUnitSignableOnly() {
        FaultMapper faults = mock(FaultMapper.class);
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        MiniappMenuService menus = new MiniappMenuService(faults, records, userRoles, elevators);

        Employee me = emp("emp_zw", "王芳", "CLIENT_ADMIN");
        when(userRoles.selectList(any())).thenReturn(List.of(
                ur("emp_zw", "CLIENT_ADMIN", "uu_1"), ur("emp_zw", "CLIENT_SIGNER", "uu_1")));
        Elevator own = new Elevator();
        own.elevatorCode = "DT-1";
        own.useUnitId = "uu_1";
        when(elevators.selectList(any())).thenReturn(List.of(own));
        MaintainRecord signable = rec("ur_1", "DT-1", "PENDING_CLIENT_SIGNATURE", "PENDING");
        MaintainRecord superseded = rec("ur_2", "DT-1", "PENDING_CLIENT_SIGNATURE", "PENDING");
        MaintainRecord otherUnit = rec("ur_3", "DT-2", "PENDING_CLIENT_SIGNATURE", "PENDING"); // uu_2 的电梯
        MaintainRecord notYet = rec("ur_4", "DT-1", "PENDING_CLIENT_SIGN", "PENDING");         // 双签未齐
        when(records.selectList(any())).thenReturn(List.of(signable, superseded, otherUnit, notYet));
        when(records.selectCount(any())).thenReturn(0L, 1L); // ur_1=链末端；ur_2=存在后续版本

        Map<String, Object> out = menus.menusOf(Set.of("CLIENT_ADMIN"), null, me);
        Map<String, Object> badges = (Map<String, Object>) out.get("badges");

        assertEquals(1L, badges.get("pendingSigns"), "只计 本单位+可签+最新版本（ur_1）");
        assertEquals(0L, badges.get("pendingOrders"));
    }

    /** 无锚点 fail-closed：计 0 且不再全表 count（修复跨单位数量泄漏的旧实现路径） */
    @Test
    @SuppressWarnings("unchecked")
    void clientBadgeWithoutAnchorFailClosed() {
        FaultMapper faults = mock(FaultMapper.class);
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        MiniappMenuService menus = new MiniappMenuService(faults, records, userRoles, elevators);

        Employee me = emp("emp_zw", "王芳", "CLIENT_ADMIN");
        when(userRoles.selectList(any())).thenReturn(List.of(ur("emp_zw", "CLIENT_ADMIN", null)));

        Map<String, Object> out = menus.menusOf(Set.of("CLIENT_ADMIN"), null, me);
        Map<String, Object> badges = (Map<String, Object>) out.get("badges");

        assertEquals(0L, badges.get("pendingSigns"));
        verify(records, never()).selectList(any());
        verify(records, never()).selectCount(any()); // 绝不回退全表计数
        verify(elevators, never()).selectList(any());
    }
}
