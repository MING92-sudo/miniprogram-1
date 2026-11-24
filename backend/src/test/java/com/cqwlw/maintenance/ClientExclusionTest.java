package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MiniappMenuService;
import com.cqwlw.maintenance.common.BizException;
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
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                mock(PhoneMutexService.class), mock(WorkOrderMapper.class), mock(PlatformClient.class),
                mock(com.cqwlw.maintenance.mapper.SysOrgMapper.class),
                mock(com.cqwlw.maintenance.service.AuditLogService.class));
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
        when(userRoles.selectList(any())).thenReturn(List.of(
                ur("emp_zw", "CLIENT_ADMIN", "uu_1"),
                ur("emp_n", "CLIENT_SIGNER", "uu_1"),
                ur("emp_m", "CLIENT_ADMIN", "uu_1")));

        List<Map<String, Object>> out = archiveService(employees, userRoles).employeeList();

        assertTrue(out.stream().noneMatch(m -> String.valueOf(m.get("role")).startsWith("CLIENT")),
                "人员目录不得出现 CLIENT_* 行");
        assertEquals(List.of("emp_1", "emp_m"), out.stream().map(m -> m.get("id")).toList(),
                "UNIT_ADMIN 旧码 / 主角色缺失的全客户账号同样排除，仅内部主体保留");
        // 查询次数锁定：角色表至多 1 次批量查询，不随员工数增长
        verify(userRoles, atMost(1)).selectList(any());
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
        UserController controller = new UserController(employees, userRoles);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(Set.of("SYS_ADMIN"));

        List<Map<String, Object>> out = controller.list(req).getData();

        assertTrue(out.stream().noneMatch(m -> ((List<?>) m.get("roles")).stream()
                .anyMatch(r -> String.valueOf(r).startsWith("CLIENT"))), "账号视图不得出现 CLIENT 角色行");
        assertEquals(List.of("emp_1"), out.stream().map(m -> m.get("id")).toList());
        // 小程序人员台账列：账号/角色文案/所属区域/未闭环工单必须下发（缺 key 恒 '—'）
        assertTrue(out.get(0).containsKey("account"));
        assertTrue(out.get(0).containsKey("roleText"));
        assertTrue(out.get(0).containsKey("regionName"));
        assertTrue(out.get(0).containsKey("openOrders"));
    }

    // ── ② 组织树不渲染 UNIT 节点 ──

    @Test
    void orgTreeExcludesUnitNodes() {
        SysOrgMapper orgMapper = mock(SysOrgMapper.class);
        when(orgMapper.selectList(any())).thenReturn(List.of(
                org("org_c", "COMPANY"), org("org_d", "DEPT"), org("org_t", "TEAM"), org("org_u", "UNIT")));
        LedgerService service = new LedgerService(orgMapper, mock(BizProjectMapper.class),
                mock(SysInviteMapper.class), mock(BizElevatorChangeMapper.class),
                mock(ElevatorMapper.class), mock(EmployeeMapper.class), mock(RegionMapper.class),
                mock(AuditLogService.class));

        List<Map<String, Object>> out = service.orgTree();

        assertTrue(out.stream().noneMatch(o -> "UNIT".equals(o.get("type"))), "内部组织树不得渲染 UNIT 节点");
        assertEquals(List.of("org_c", "org_d", "org_t"), out.stream().map(o -> o.get("id")).toList());
    }

    /** 组织树投影：负责人 leaderId 解析为姓名/电话，账号数按成员归属谓词聚合 */
    @Test
    void orgTreeEnrichesLeaderAndAccountCount() {
        SysOrgMapper orgMapper = mock(SysOrgMapper.class);
        SysOrg team = org("org_t", "TEAM");
        team.name = "维保一班";
        team.leaderId = "emp_leader";
        when(orgMapper.selectList(any())).thenReturn(List.of(team));
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        Employee leader = new Employee();
        leader.id = "emp_leader";
        leader.name = "张班长";
        leader.phone = "13800000001";
        leader.groupName = "维保一班";
        Employee member = new Employee();
        member.id = "emp_m1";
        member.groupName = "维保一班";
        Employee outsider = new Employee();
        outsider.id = "emp_m2";
        outsider.groupName = "维保二班";
        when(employeeMapper.selectList(any())).thenReturn(List.of(leader, member, outsider));
        LedgerService service = new LedgerService(orgMapper, mock(BizProjectMapper.class),
                mock(SysInviteMapper.class), mock(BizElevatorChangeMapper.class),
                mock(ElevatorMapper.class), employeeMapper, mock(RegionMapper.class),
                mock(AuditLogService.class));

        List<Map<String, Object>> out = service.orgTree();

        assertEquals("张班长", out.get(0).get("leaderName"));
        assertEquals("13800000001", out.get(0).get("leaderPhone"));
        assertEquals(2L, out.get(0).get("accountCount"));
    }

    // ── ②' 使用单位不得作为内部组织新建（外部客户主体走「使用单位档案」） ──

    @Test
    void createOrgRejectsUnitType() {
        LedgerService service = new LedgerService(mock(SysOrgMapper.class), mock(BizProjectMapper.class),
                mock(SysInviteMapper.class), mock(BizElevatorChangeMapper.class),
                mock(ElevatorMapper.class), mock(EmployeeMapper.class), mock(RegionMapper.class),
                mock(AuditLogService.class));

        BizException ex = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "UNIT", "name", "某物业公司"), "emp_1"));

        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("使用单位档案"), "应引导至「使用单位档案」开通");
    }


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


    /** brief Step 1：CLIENT_ADMIN(uu_1) pendingSigns 只数本单位可签，不含 uu_2；不计 PENDING_CLIENT_SIGN、不计旧版本 */
    @Test
    @SuppressWarnings("unchecked")
    void clientBadgeCountsOwnUnitSignableOnly() {
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        MiniappMenuService menus = new MiniappMenuService(records, userRoles, elevators);

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
        MaintainRecord nextOfSuperseded = rec("ur_2_v2", "DT-1", "SIGNED", "CONFIRMED");
        nextOfSuperseded.prevVersionId = "ur_2"; // supersededIds 批量 IN 查询命中的后续版本行
        when(records.selectList(any()))
                .thenReturn(List.of(signable, superseded, otherUnit, notYet))
                .thenReturn(List.of(nextOfSuperseded));

        Map<String, Object> out = menus.menusOf(Set.of("CLIENT_ADMIN"), null, me);
        Map<String, Object> badges = (Map<String, Object>) out.get("badges");

        assertEquals(1L, badges.get("pendingSigns"), "只计 本单位+可签+最新版本（ur_1）");
        assertEquals(0L, badges.get("pendingOrders"));
        verify(records, never()).selectCount(any());
        verify(records, atMost(2)).selectList(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientBadgeWithoutAnchorFailClosed() {
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        SysUserRoleMapper userRoles = mock(SysUserRoleMapper.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        MiniappMenuService menus = new MiniappMenuService(records, userRoles, elevators);

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
