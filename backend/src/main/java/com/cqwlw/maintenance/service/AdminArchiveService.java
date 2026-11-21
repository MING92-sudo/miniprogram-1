package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端档案维护（docs/09 一期：维保单位/使用单位/人员/电梯四类档案）。
 * 写接口一律：管理端写角色门禁（拦截器）+ X-Idempotency-Key（控制器）+ 手机号互斥预校验（1002+conflicts[]）。
 * 电梯读接口沿用 GET /elevators（小程序共用），管理端用 GET /admin/elevators 富视图（含 geoStatus）。
 */
@Service
public class AdminArchiveService {

    private final CompanyMapper companyMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final ElevatorMapper elevatorMapper;
    private final PhoneMutexService phoneMutexService;
    private final WorkOrderMapper workOrderMapper;
    private final PlatformClient platformClient;
    /** 组织树（调人校验：目标班组必须等于某个 status≠DISABLED 的 TEAM 节点名） */
    private final SysOrgMapper sysOrgMapper;
    /** 业务审计（班组变更留痕；落库失败吞异常不影响主流程，同 GrantService 口径） */
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** 区域名称解析（电梯档案「所在区域」展示）；setter 注入保持既有构造器签名不变（单测不传） */
    private com.cqwlw.maintenance.mapper.RegionMapper regionMapper;
    /** 客户账号：删使用单位时级联清理（setter 注入，单测构造器不传时跳过） */
    private com.cqwlw.maintenance.mapper.ClientAccountMapper clientAccountMapper;
    /** 维保记录：删单位前解绑客户签字外键，避免悬空引用 */
    private com.cqwlw.maintenance.mapper.MaintainRecordMapper maintainRecordMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegionMapper(com.cqwlw.maintenance.mapper.RegionMapper regionMapper) {
        this.regionMapper = regionMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientAccountMapper(com.cqwlw.maintenance.mapper.ClientAccountMapper mapper) {
        this.clientAccountMapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMaintainRecordMapper(com.cqwlw.maintenance.mapper.MaintainRecordMapper mapper) {
        this.maintainRecordMapper = mapper;
    }

    public AdminArchiveService(CompanyMapper companyMapper, UseUnitMapper useUnitMapper,
                               EmployeeMapper employeeMapper, SysUserRoleMapper userRoleMapper,
                               ElevatorMapper elevatorMapper,
                               PhoneMutexService phoneMutexService,
                               WorkOrderMapper workOrderMapper, PlatformClient platformClient,
                               SysOrgMapper sysOrgMapper, AuditLogService auditLogService) {
        this.companyMapper = companyMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
        this.elevatorMapper = elevatorMapper;
        this.phoneMutexService = phoneMutexService;
        this.workOrderMapper = workOrderMapper;
        this.platformClient = platformClient;
        this.sysOrgMapper = sysOrgMapper;
        this.auditLogService = auditLogService;
    }

    // ── 维保单位（GET/PUT /company）──

    public Map<String, Object> companyView() {
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(1404, "维保单位档案不存在"));
        return companyRow(c);
    }

    public Map<String, Object> updateCompany(Map<String, Object> body) {
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(1404, "维保单位档案不存在"));
        if (body.get("name") != null) {
            c.name = str(body, "name");
        }
        if (body.get("organizationCode") != null) {
            c.organizationCode = str(body, "organizationCode");
        }
        if (body.get("workMenegerName") != null) {
            c.workMenegerName = str(body, "workMenegerName");
        }
        if (body.get("workMenegerPhone") != null) {
            c.workMenegerPhone = str(body, "workMenegerPhone");
        }
        requireNoConflict(phoneMutexService.checkCompany(c.workMenegerPhone));
        companyMapper.updateById(c);
        return companyRow(c);
    }

    // ── 使用单位（GET/POST/PUT /use-units）──

    public List<Map<String, Object>> useUnitList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UseUnit u : useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>()
                .orderByAsc(UseUnit::getId))) {
            Map<String, Object> m = useUnitRow(u);
            m.put("elevatorCount", elevatorMapper.selectCount(new LambdaQueryWrapper<Elevator>()
                    .eq(Elevator::getUseUnitId, u.id)));
            list.add(m);
        }
        return list;
    }

    public Map<String, Object> createUseUnit(Map<String, Object> body) {
        if (str(body, "unitName").isBlank()) {
            throw new BizException(422, "使用单位名称必填");
        }
        UseUnit u = new UseUnit();
        u.id = Ids.next("uu");
        applyUseUnit(u, body);
        requireNoConflict(phoneMutexService.checkUseUnit(u.id, u.unitPrincipalPhone, u.elevatorAdministerPhone));
        useUnitMapper.insert(u);
        return useUnitRow(u);
    }

    public Map<String, Object> updateUseUnit(String id, Map<String, Object> body) {
        UseUnit u = useUnitMapper.selectById(id);
        if (u == null) {
            throw new BizException(1404, "使用单位不存在");
        }
        applyUseUnit(u, body);
        requireNoConflict(phoneMutexService.checkUseUnit(u.id, u.unitPrincipalPhone, u.elevatorAdministerPhone));
        useUnitMapper.updateById(u);
        return useUnitRow(u);
    }

    // ── 人员（GET/POST/PUT /employees）──

    /**
     * 人员目录（GET /employees）。P1（T5）临时排除客户主体行：客户账号在「使用单位档案」下开通
     * （docs/14 §9.1），不进内部人员目录视图；数据保留不删。判定用 principalTypeOf 而非
     * department 字面量（客户 department 存 UNIT/null 两代值）。P3 账号体系重建后再定正式口径。
     */
    public List<Map<String, Object>> employeeList() {
        List<Employee> all = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .orderByAsc(Employee::getId));
        // P2 T5（N+1 消除）：一次 .in(userId) 批量载入全部员工 sys_user_role（EMPLOYEE 行）按 userId 分组，
        // 替代逐员工查询；每员工角色集与旧 .eq(userId) 逐条查询逐集合相等，排除结论不变
        Map<String, List<SysUserRole>> rolesByUser = employeeRolesByUser(all);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Employee e : all == null ? List.<Employee>of() : all) {
            if (customerPrincipal(e, rolesByUser)) {
                continue;
            }
            list.add(employeeRow(e));
        }
        return list;
    }

    /**
     * 批量载入员工角色行并按 userId 分组（P2 T5）。
     * 空 id 集短路不发起查询——MyBatis-Plus in() 空集会退化为无条件（全表），必须守卫；
     * null userId 行不入分组（旧 .eq(userId) 语义下 NULL 永不匹配任何员工）。
     */
    private Map<String, List<SysUserRole>> employeeRolesByUser(List<Employee> all) {
        Map<String, List<SysUserRole>> map = new LinkedHashMap<>();
        Set<String> empIds = new LinkedHashSet<>();
        for (Employee e : all == null ? List.<Employee>of() : all) {
            if (e != null && e.id != null) {
                empIds.add(e.id);
            }
        }
        if (empIds.isEmpty()) {
            return map;
        }
        List<SysUserRole> rows = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .in(SysUserRole::getUserId, empIds)
                .eq(SysUserRole::getUserType, "EMPLOYEE"));
        for (SysUserRole ur : rows == null ? List.<SysUserRole>of() : rows) {
            if (ur != null && ur.userId != null) {
                map.computeIfAbsent(ur.userId, k -> new ArrayList<>()).add(ur);
            }
        }
        return map;
    }

    /** 生效角色集 = 主角色 ∪ sys_user_role（EMPLOYEE 行，employeeRolesByUser 批量预载）：任一内部即保留，全 CUSTOMER 才排除 */
    private boolean customerPrincipal(Employee e, Map<String, List<SysUserRole>> rolesByUser) {
        Set<String> roles = new LinkedHashSet<>();
        if (e.role != null && !e.role.isBlank()) {
            roles.add(e.role);
        }
        // null id 无角色行（与旧 .eq(userId, null) 恒不匹配同口径；且不可直接 getOrDefault(null)——不可变空 Map 会 NPE）
        for (SysUserRole ur : e.id == null ? List.<SysUserRole>of() : rolesByUser.getOrDefault(e.id, List.of())) {
            roles.add(ur.roleCode);
        }
        return RoleCatalog.isCustomerPrincipal(roles);
    }

    /** 2.4 自动建档用：按证书号判重 */
    public Long employeeCertificateExists(String certificate) {
        if (certificate == null || certificate.isBlank()) {
            return 1L;
        }
        return employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getCertificate, certificate));
    }

    public Map<String, Object> createEmployee(Map<String, Object> body) {
        String name = str(body, "name");
        String phone = str(body, "phone");
        String role = str(body, "role");
        if (name.isBlank() || phone.isBlank()) {
            throw new BizException(422, "姓名与手机号必填");
        }
        if (!AdminRoles.assignable(role)) {
            throw new BizException(422, "角色不合法：" + role + "（使用单位账号在「使用单位档案」开通，不建在人员目录）");
        }
        String account = str(body, "account").isBlank() ? phone : str(body, "account");
        Long dup = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getAccount, account));
        if (dup != null && dup > 0) {
            throw new BizException(422, "账号已存在：" + account);
        }
        requireNoConflict(phoneMutexService.checkEmployee(null, role, phone));
        Employee e = new Employee();
        e.id = Ids.next("emp");
        e.name = name;
        e.phone = phone;
        e.account = account;
        e.role = role;
        e.department = RoleCatalog.departmentOf(role); // V31：部门由角色派生，供部门数据域谓词使用
        e.roleText = str(body, "roleText").isBlank() ? defaultRoleText(role) : str(body, "roleText");
        e.groupName = str(body, "groupName");
        // 所属区域（region_id，选填；区域工单池前置）：空串=清除；非空须存在（口径同电梯 applyElevator）
        if (body.get("regionId") != null) {
            String regionId = str(body, "regionId");
            if (regionId.isBlank()) {
                e.regionId = null;
            } else {
                if (regionMapper == null || regionMapper.selectById(regionId) == null) {
                    throw new BizException(422, "区域不存在：" + regionId);
                }
                e.regionId = regionId;
            }
        }
        String password = str(body, "password");
        if (!password.isBlank() && password.length() < 8) {
            throw new BizException(422, "请设置至少 8 位初始密码");
        }
        String initialPassword = password.isBlank() ? randomPassword() : password;
        e.passwordHash = encoder.encode(initialPassword);
        e.platformId = str(body, "platformId");
        e.certificate = str(body, "certificate");
        e.workStartDate = str(body, "workStartDate");
        e.workEndDate = str(body, "workEndDate");
        e.workStat = str(body, "workStat").isBlank() ? "normal" : str(body, "workStat");
        e.syncStatus = str(body, "syncStatus").isBlank() ? "NOT_SYNCED" : str(body, "syncStatus");
        e.enabled = true;
        employeeMapper.insert(e);
        // 账号管理页 /users 的角色列只读 sys_user_role；建档（含平台登记自动建号）必须同步写一行，
        // 否则新账号在账号管理页显示为空角色。口径与 UserController.insertRole 一致。
        insertPrimaryRole(e.id, role);
        Map<String, Object> out = employeeRow(e);
        out.put("initialPassword", initialPassword);
        return out;
    }

    /** 建档时写入主角色行（EMPLOYEE）；createEmployee 仅允许非客户角色，故 orgId 留空。 */
    private void insertPrimaryRole(String userId, String role) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        SysUserRole ur = new SysUserRole();
        ur.id = Ids.next("ur");
        ur.userId = userId;
        ur.userType = "EMPLOYEE";
        ur.roleCode = role;
        ur.createdAt = TimeUtil.now();
        userRoleMapper.insert(ur);
    }

    /** 用户需求④：初始/重置密码为 12 位无易混字符随机串，一次性展示，人员自助改密 */
    private String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 档案删除（①增删改查补全）：有在途工单或系统管理员账号禁止删除，其余允许 */
    public Map<String, Object> deleteEmployee(String id) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, "人员不存在");
        }
        if ("SYS_ADMIN".equals(e.role)) {
            throw new BizException(422, "系统管理员账号不可删除（可停用）");
        }
        Long inflight = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getStatus, List.of("PENDING", "PROCESSING"))
                .and(w -> w.eq(WorkOrder::getWorkerName, e.name).or().eq(WorkOrder::getAssistantName, e.name)));
        if (inflight != null && inflight > 0) {
            throw new BizException(422, "该人员名下有在途工单（待执行/进行中），暂不能删除");
        }
        if (e.platformId != null && !e.platformId.isBlank()
                && (e.bindStatus == null || e.bindStatus == 0)) {
            throw new BizException(422, "该人员已在平台登记且绑定正常，请先在人员管理中止后再删除");
        }
        employeeMapper.deleteById(id);
        return Map.of("ok", true);
    }

    /** 2.4 登记建立/中止后回写绑定状态（按证书号定位，平台内唯一） */
    public void updateBindStatusByCertificate(String certificate, int bindStatus) {
        Employee e = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getCertificate, certificate)).stream().findFirst().orElse(null);
        if (e != null) {
            e.bindStatus = bindStatus;
            employeeMapper.updateById(e);
        }
    }

    public Map<String, Object> deleteUseUnit(String id) {
        UseUnit u = useUnitMapper.selectById(id);
        if (u == null) {
            throw new BizException(1404, "使用单位不存在");
        }
        Long elevators = elevatorMapper.selectCount(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getUseUnitId, id));
        if (elevators != null && elevators > 0) {
            throw new BizException(422, "该单位名下有 " + elevators + " 台电梯，请先移除关联后再删除");
        }
        // 客户账号是使用单位的附庸：单位删除后账号必须一并失效，否则「单位已删、账号仍可登录」，
        // 登录后归属单位悬空（跳转到不存在的租户）。签字历史保留姓名快照，仅解绑外键避免悬空引用。
        int removedAccounts = 0;
        if (maintainRecordMapper != null) {
            maintainRecordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                    .eq(MaintainRecord::getUseUnitId, id)
                    .set(MaintainRecord::getClientAccountId, null)
                    .set(MaintainRecord::getRefusedByClientAccountId, null));
        }
        if (clientAccountMapper != null) {
            removedAccounts = clientAccountMapper.delete(new LambdaQueryWrapper<ClientAccount>()
                    .eq(ClientAccount::getUseUnitId, id));
        }
        useUnitMapper.deleteById(id);
        return Map.of("ok", true, "removedClientAccounts", removedAccounts);
    }

    public Map<String, Object> deleteElevator(String id) {
        Elevator el = elevatorMapper.selectById(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        Long inflight = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, id)
                .in(WorkOrder::getStatus, List.of("PENDING", "PROCESSING")));
        if (inflight != null && inflight > 0) {
            throw new BizException(422, "该电梯有在途工单（待执行/进行中），暂不能删除");
        }
        elevatorMapper.deleteById(id);
        return Map.of("ok", true);
    }

    /** 账号启停（SYS_ADMIN，docs/04 A.1 用户权限） */
    public Map<String, Object> setEmployeeEnabled(String id, boolean enabled) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, "人员不存在");
        }
        e.enabled = enabled;
        employeeMapper.updateById(e);
        return employeeRow(e);
    }

    /** 重置密码（SYS_ADMIN，docs/04 A.1 用户权限） */
    public Map<String, Object> resetEmployeePassword(String id, String password) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, "人员不存在");
        }
        boolean random = password == null || password.isBlank();
        if (!random && password.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        String initial = random ? randomPassword() : password;
        e.passwordHash = encoder.encode(initial);
        employeeMapper.updateById(e);
        Map<String, Object> out = employeeRow(e);
        out.put("initialPassword", initial);
        return out;
    }

    public Map<String, Object> updateEmployee(String id, Map<String, Object> body, String actorId) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, "人员不存在");
        }
        String oldGroupName = e.groupName;
        if (body.get("name") != null) {
            e.name = str(body, "name");
        }
        if (body.get("phone") != null) {
            String newPhone = str(body, "phone");
            // 登录按 account 查（AuthController）；默认账号=手机号，改号时同步，避免改完手机号登录不了。
            // 仅在 account 仍等于旧手机号（默认账号）时同步，自定义账号不覆盖。
            if (e.account == null || e.account.isBlank() || e.account.equals(e.phone)) {
                e.account = newPhone;
            }
            e.phone = newPhone;
        }
        boolean roleChanged = false;
        if (body.get("role") != null) {
            if (!AdminRoles.assignable(str(body, "role"))) {
                throw new BizException(422, "角色不合法：" + str(body, "role") + "（使用单位账号在「使用单位档案」开通）");
            }
            String oldRole = e.role;
            e.role = str(body, "role");
            e.department = RoleCatalog.departmentOf(e.role); // 改角色同步部门，避免旧部门谓词残留
            e.roleText = str(body, "roleText").isBlank() ? defaultRoleText(e.role) : str(body, "roleText");
            roleChanged = !java.util.Objects.equals(oldRole, e.role);
        } else if (body.get("roleText") != null) {
            e.roleText = str(body, "roleText");
        }
        if (body.get("certificate") != null) {
            e.certificate = str(body, "certificate");
        }
        if (body.get("workStartDate") != null) {
            e.workStartDate = str(body, "workStartDate");
        }
        if (body.get("workEndDate") != null) {
            e.workEndDate = str(body, "workEndDate");
        }
        if (body.get("workStat") != null) {
            e.workStat = str(body, "workStat");
        }
        if (body.get("syncStatus") != null) {
            e.syncStatus = str(body, "syncStatus");
        }
        if (body.get("platformId") != null) {
            e.platformId = str(body, "platformId");
        }
        if (body.get("groupName") != null) {
            String newGroup = str(body, "groupName");
            // 调人校验（D1）：目标班组非空且与旧值不同时，必须等于组织树中某个 type=TEAM 且
            // status≠DISABLED 节点的 name；空串=清除班组不校验。校验失败不落库（不改任何状态）。
            if (!newGroup.isBlank() && !newGroup.equals(nz(oldGroupName))) {
                boolean teamExists = sysOrgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                        .eq(SysOrg::getType, "TEAM")
                        .eq(SysOrg::getName, newGroup)).stream()
                        .anyMatch(o -> !"DISABLED".equals(o.status));
                if (!teamExists) {
                    throw new BizException(422, "目标班组不存在");
                }
            }
            e.groupName = newGroup;
        }
        // 所属区域（region_id，选填；区域工单池前置）：null=不动 / 空串=清除 / 非空须存在（口径同电梯）
        if (body.get("regionId") != null) {
            String regionId = str(body, "regionId");
            if (regionId.isBlank()) {
                e.regionId = null;
            } else {
                if (regionMapper == null || regionMapper.selectById(regionId) == null) {
                    throw new BizException(422, "区域不存在：" + regionId);
                }
                e.regionId = regionId;
            }
        }
        if (!str(body, "password").isBlank()) {
            e.passwordHash = encoder.encode(str(body, "password"));
        }
        requireNoConflict(phoneMutexService.checkEmployee(e.id, e.role, e.phone));
        employeeMapper.updateById(e);
        // 所属区域置空必须显式 SET NULL——updateById 默认跳过 null 字段（同电梯 clearRegion 口径）
        if (body.get("regionId") != null && str(body, "regionId").isBlank()) {
            employeeMapper.update(null, new LambdaUpdateWrapper<Employee>()
                    .eq(Employee::getId, e.id)
                    .set(Employee::getRegionId, null));
        }
        // 改角色时同步 sys_user_role 主角色行，保持账号管理页角色列与权限判定一致。
        // 仅当该账号只有主角色一行时对齐（多角色账号由「分配角色」端点专门管理，不在此覆盖）。
        if (roleChanged) {
            List<SysUserRole> existing = userRoleMapper.selectList(
                    new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, e.id));
            if (existing == null || existing.size() <= 1) {
                insertPrimaryRole(e.id, e.role);
            }
        }
        // 调人审计（D1）：班组实际发生变化（含清除为空）时留痕——操作人、员工 id、原班组→新班组
        if (body.get("groupName") != null && !java.util.Objects.equals(nz(oldGroupName), nz(e.groupName))) {
            appendGroupChangeAudit(actorId, e.id, oldGroupName, e.groupName);
        }
        return employeeRow(e);
    }

    /** 调人审计：落库失败吞异常不影响主流程（AuditLogService 契约，同 GrantService/ClientAccountService 口径） */
    private void appendGroupChangeAudit(String actorId, String employeeId, String oldGroup, String newGroup) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.actorId = actorId;
            a.actorRole = "EMPLOYEE";
            a.action = "employee:change-group";
            a.target = employeeId;
            a.reason = "班组变更：" + nz(oldGroup) + " → " + nz(newGroup);
            a.result = "SUCCESS";
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(AdminArchiveService.class)
                    .warn("班组变更审计写入失败: target={}", employeeId);
        }
    }

    // ── 电梯（POST /elevators、PUT /elevators/{id}；管理端富视图 GET /admin/elevators）──

    public List<Map<String, Object>> elevatorAdminList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Elevator el : elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .orderByAsc(Elevator::getId))) {
            list.add(elevatorAdminRow(el));
        }
        return list;
    }

    public Map<String, Object> createElevator(Map<String, Object> body) {
        Elevator el = new Elevator();
        el.id = Ids.next("el");
        applyElevator(el, body);
        // 设备信息档案 5 项必传（电梯编码/设备代码/注册代码/单位内编号/出厂编号，docs/01 §3.4.1）
        DeviceProfileValidator.requireForCreate(el);
        // elevator_name 为库表 NOT NULL：未填项目名称时以单位内编号（梯号）兜底，再退电梯编码
        if (isBlank(el.elevatorName)) {
            el.elevatorName = isBlank(el.insideNumber) ? el.elevatorCode : el.insideNumber;
        }
        // 区域抢单池（D2/M6）：建档不再写维保人员绑定（worker*/assistant*），绑定手机号互斥预校验随之取消
        // 建档即排期（用户口径 2026-11-20：首次建档就把一年维保计划排出来，进维保日历）：
        // 基线缺省 = 建档时间（维保责任起始，AGENTS §3.6「无已完成维保回退建档 lastMaintenanceAt」）；
        // 下次到期缺省 = 基线 + intervalDays（缺省 15）= 首保计划日。此后到期前一日定时派单接管、
        // 签退滚动 nextMaintenanceDate，日历从锚点向前投影全年——直建电梯不再需要手动首保派单。
        if (el.lastMaintenanceAt == null) {
            el.lastMaintenanceAt = TimeUtil.now();
        }
        if (el.nextMaintenanceDate == null) {
            el.nextMaintenanceDate = el.lastMaintenanceAt.toLocalDate()
                    .plusDays(el.intervalDays != null ? el.intervalDays : 15);
        }
        elevatorMapper.insert(el);
        return elevatorAdminRow(el);
    }

    public Map<String, Object> updateElevator(String id, Map<String, Object> body) {
        Elevator el = elevatorMapper.selectById(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        // 必传项不得清空：上传维保记录依赖这 5 项，清空会让设备档案与记录单脱钩
        for (DeviceProfileValidator.Field f : DeviceProfileValidator.REQUIRED) {
            if (body.containsKey(f.key()) && isBlank(str(body, f.key()))) {
                throw new BizException(422, f.label() + "为上传维保记录的必传项，不能清空");
            }
        }
        applyElevator(el, body);
        if (isBlank(el.elevatorName)) {
            el.elevatorName = isBlank(el.insideNumber) ? nz(el.elevatorCode) : el.insideNumber;
        }
        elevatorMapper.updateById(el);
        // 经纬度清空（留空保存）必须显式置 NULL——updateById 默认跳过 null 字段，
        // 否则错误的首采坐标永远无法清除（清空后下次签到重新现场采集，docs/02 §5.4）
        boolean clearLng = body.get("lng") != null && str(body, "lng").isBlank();
        boolean clearLat = body.get("lat") != null && str(body, "lat").isBlank();
        // 所在区域置空同理（updateById 跳过 null 字段，必须显式 SET NULL）
        boolean clearRegion = body.get("regionId") != null && str(body, "regionId").isBlank();
        // 停保恢复（status=ACTIVE/空 → 在保）同理：applyElevator 置 null 后 updateById 跳过，
        // 不显式 SET NULL 会导致「启用」永远写不回（docs/09 §6.6：NULL=在保）
        boolean clearStatus = body.get("status") != null && !"INACTIVE".equals(str(body, "status"));
        if (clearLng || clearLat || clearRegion || clearStatus) {
            LambdaUpdateWrapper<Elevator> uw = new LambdaUpdateWrapper<Elevator>().eq(Elevator::getId, id);
            if (clearLng) {
                uw.set(Elevator::getLng, null);
            }
            if (clearLat) {
                uw.set(Elevator::getLat, null);
            }
            if (clearRegion) {
                uw.set(Elevator::getRegionId, null);
            }
            if (clearStatus) {
                uw.set(Elevator::getStatus, null);
            }
            elevatorMapper.update(null, uw);
        }
        return elevatorAdminRow(el);
    }

    // ── 私有 ──

    private void applyUseUnit(UseUnit u, Map<String, Object> body) {
        if (body.get("unitName") != null) {
            u.unitName = str(body, "unitName");
        }
        if (body.get("organizationCode") != null) {
            u.organizationCode = str(body, "organizationCode");
        }
        if (body.get("unitPrincipal") != null) {
            u.unitPrincipal = str(body, "unitPrincipal");
        }
        if (body.get("unitPrincipalPhone") != null) {
            u.unitPrincipalPhone = str(body, "unitPrincipalPhone");
        }
        if (body.get("elevatorAdminister") != null) {
            u.elevatorAdminister = str(body, "elevatorAdminister");
        }
        if (body.get("elevatorAdministerPhone") != null) {
            u.elevatorAdministerPhone = str(body, "elevatorAdministerPhone");
        }
        if (body.get("emergencyPhone") != null) {
            u.emergencyPhone = str(body, "emergencyPhone");
        }
        if (body.get("entityId") != null) {
            u.entityId = str(body, "entityId");
        }
    }

    /** 经纬度是签到围栏依据：创建/更新必须成对提交，且非法输入统一转 422，不得带异常落库。 */
    private void validateElevatorGeo(Map<String, Object> body) {
        boolean hasLng = body.containsKey("lng");
        boolean hasLat = body.containsKey("lat");
        if (!hasLng && !hasLat) {
            return;
        }
        if (!hasLng || !hasLat) {
            throw new BizException(422, "经纬度须同时填写或同时留空");
        }
        String lngText = str(body, "lng");
        String latText = str(body, "lat");
        if (lngText.isBlank() && latText.isBlank()) {
            return;
        }
        if (lngText.isBlank() || latText.isBlank()) {
            throw new BizException(422, "经纬度须同时填写或同时留空");
        }
        BigDecimal lng = parseGeo(lngText, "经度");
        BigDecimal lat = parseGeo(latText, "纬度");
        if (lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new BizException(422, "经度须在 -180 至 180 之间");
        }
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BizException(422, "纬度须在 -90 至 90 之间");
        }
    }

    private BigDecimal parseGeo(String raw, String label) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BizException(422, label + "格式不正确");
        }
    }

    private void applyElevator(Elevator el, Map<String, Object> body) {
        validateElevatorGeo(body);
        if (body.get("elevatorCode") != null) {
            el.elevatorCode = str(body, "elevatorCode");
        }
        if (body.get("location") != null) {
            el.location = str(body, "location");
        }
        // 电梯名称 = 项目名称（小区），合并维护（用户需求 2026-11-11）；projectName 优先
        if (body.get("projectName") != null) {
            el.projectName = str(body, "projectName");
            el.elevatorName = el.projectName;
        } else if (body.get("elevatorName") != null) {
            el.elevatorName = str(body, "elevatorName");
        }
        // 注册代码：对外接口字段名为 registrationCode（用户口径），DB 列沿用 reg_code；兼容旧字段 regCode
        if (body.get("registrationCode") != null) {
            el.regCode = str(body, "registrationCode");
        } else if (body.get("regCode") != null) {
            el.regCode = str(body, "regCode");
        }
        // 登记证编码（V58）：使用登记证上的独立编号（梯11渝BSP8194(20) 式），不上报监管平台
        if (body.get("certNo") != null) {
            el.certNo = str(body, "certNo");
        }
        if (body.get("deviceCode") != null) {
            el.deviceCode = str(body, "deviceCode");
        }
        if (body.get("insideNumber") != null) {
            el.insideNumber = str(body, "insideNumber");
        }
        if (body.get("model") != null) {
            el.model = str(body, "model");
        }
        if (body.get("useUnitId") != null) {
            String useUnitId = str(body, "useUnitId");
            if (!useUnitId.isBlank() && useUnitMapper.selectById(useUnitId) == null) {
                throw new BizException(422, "使用单位不存在：" + useUnitId);
            }
            el.useUnitId = useUnitId;
        }
        if (body.get("category") != null) {
            el.category = str(body, "category");
        }
        // 所在区域（region_id，选填）：空串=清空（不限区域）；非空须存在
        if (body.get("regionId") != null) {
            String regionId = str(body, "regionId");
            if (regionId.isBlank()) {
                el.regionId = null;
            } else {
                if (regionMapper == null || regionMapper.selectById(regionId) == null) {
                    throw new BizException(422, "区域不存在：" + regionId);
                }
                el.regionId = regionId;
            }
        }
        if (body.get("inspectionReportFileId") != null && !str(body, "inspectionReportFileId").isBlank()) {
            el.inspectionReportFileId = str(body, "inspectionReportFileId");
            el.inspectionReportUrl = str(body, "inspectionReportUrl");
            el.inspectionReportUploadedAt = TimeUtil.now();
            // 年检周期一年：上传新报告即滚动下次年检时间（一梯一档台账口径）
            el.nextCheckDate = TimeUtil.now().toLocalDate().plusYears(1);
        }
        if (body.get("nextCheckDate") != null) {
            el.nextCheckDate = TimeUtil.parseDate(str(body, "nextCheckDate"));
        }
        if (body.get("nextMaintenanceDate") != null) {
            el.nextMaintenanceDate = TimeUtil.parseDate(str(body, "nextMaintenanceDate"));
        }
        // 上次维保日期 = 排期基线（建档即排全年，2026-11-20 用户口径）：存量建档传真实日期（yyyy-MM-dd
        // 或 yyyy-MM-dd HH:mm:ss）；非法值 422——基线错了整张年表都错，不能静默吞掉
        if (body.get("lastMaintenanceAt") != null) {
            String raw = str(body, "lastMaintenanceAt");
            if (raw.isBlank()) {
                el.lastMaintenanceAt = null;
            } else {
                LocalDateTime parsed = TimeUtil.parse(raw);
                if (parsed == null) {
                    LocalDate d = TimeUtil.parseDate(raw);
                    parsed = d == null ? null : d.atStartOfDay();
                }
                if (parsed == null) {
                    throw new BizException(422, "上次维保日期格式无效（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）");
                }
                el.lastMaintenanceAt = parsed;
            }
        }
        if (body.get("factoryNumber") != null) {
            el.factoryNumber = str(body, "factoryNumber");
        }
        if (body.get("brand") != null) {
            el.brand = str(body, "brand");
        }
        if (body.get("manufacturer") != null) {
            el.manufacturer = str(body, "manufacturer");
        }
        if (body.get("driveMode") != null) {
            el.driveMode = str(body, "driveMode");
        }
        if (body.get("ratedLoad") != null) {
            el.ratedLoad = intOrNull(str(body, "ratedLoad"));
        }
        if (body.get("ratedLoadUnit") != null) {
            el.ratedLoadUnit = str(body, "ratedLoadUnit");
        }
        if (body.get("ratedSpeed") != null) {
            String s = str(body, "ratedSpeed");
            el.ratedSpeed = s.isBlank() ? null : new BigDecimal(s);
        }
        if (body.get("ratedSpeedUnit") != null) {
            el.ratedSpeedUnit = str(body, "ratedSpeedUnit");
        }
        if (body.get("stationsDoors") != null) {
            el.stationsDoors = str(body, "stationsDoors");
        }
        if (body.get("workTypeCode") != null) {
            el.workTypeCode = str(body, "workTypeCode");
        }
        if (body.get("intervalDays") != null) {
            el.intervalDays = intOrNull(str(body, "intervalDays"));
        }
        if (body.get("specialType") != null) {
            String s = str(body, "specialType");
            el.specialType = s.isBlank() ? null : s; // 消防/防爆/NULL（1006 自定义模板匹配）
        }
        if (body.get("elevatorAdminister") != null) {
            el.elevatorAdminister = str(body, "elevatorAdminister");
        }
        if (body.get("elevatorAdministerPhone") != null) {
            el.elevatorAdministerPhone = str(body, "elevatorAdministerPhone");
        }
        if (body.get("emergencyPhone") != null) {
            el.emergencyPhone = str(body, "emergencyPhone");
        }
        if (body.get("lng") != null) {
            String s = str(body, "lng");
            el.lng = s.isBlank() ? null : new BigDecimal(s);
        }
        if (body.get("lat") != null) {
            String s = str(body, "lat");
            el.lat = s.isBlank() ? null : new BigDecimal(s);
        }
        if (body.get("elevatorAdminister") != null) {
            el.elevatorAdminister = str(body, "elevatorAdminister");
        }
        if (body.get("elevatorAdministerPhone") != null) {
            el.elevatorAdministerPhone = str(body, "elevatorAdministerPhone");
        }
        if (body.get("emergencyPhone") != null) {
            el.emergencyPhone = str(body, "emergencyPhone");
        }
        if (body.get("useUnitEntityId") != null) {
            el.useUnitEntityId = str(body, "useUnitEntityId");
        }
        if (body.get("status") != null) {
            String s = str(body, "status");
            // 停保=INACTIVE；空/ACTIVE=恢复在保（docs/09 §6.6：停保后不派单、小程序不可见）
            el.status = "INACTIVE".equals(s) ? "INACTIVE" : null;
        }
    }

    private Map<String, Object> companyRow(Company c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", nz(c.name));
        m.put("organizationCode", nz(c.organizationCode));
        m.put("entityId", nz(c.entityId));
        m.put("orgId", nz(c.orgId));
        m.put("workMenegerName", nz(c.workMenegerName));
        m.put("workMenegerPhone", nz(c.workMenegerPhone));
        return m;
    }

    private Map<String, Object> useUnitRow(UseUnit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("unitName", nz(u.unitName));
        m.put("organizationCode", nz(u.organizationCode));
        m.put("unitPrincipal", nz(u.unitPrincipal));
        m.put("unitPrincipalPhone", nz(u.unitPrincipalPhone));
        m.put("elevatorAdminister", nz(u.elevatorAdminister));
        m.put("elevatorAdministerPhone", nz(u.elevatorAdministerPhone));
        m.put("emergencyPhone", nz(u.emergencyPhone));
        m.put("entityId", nz(u.entityId));
        return m;
    }

    private Map<String, Object> employeeRow(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("name", nz(e.name));
        m.put("phone", nz(e.phone));
        m.put("account", nz(e.account));
        m.put("role", nz(e.role));
        m.put("roleText", nz(e.roleText));
        m.put("department", nz(e.department));
        m.put("departmentText", RoleCatalog.departmentName(e.department));
        m.put("groupName", nz(e.groupName));
        m.put("regionId", nz(e.regionId));
        m.put("regionName", regionNameOf(e.regionId));
        m.put("platformId", nz(e.platformId));
        m.put("certificate", nz(e.certificate));
        m.put("workStartDate", nz(e.workStartDate));
        m.put("workEndDate", nz(e.workEndDate));
        m.put("workStat", nz(e.workStat));
        m.put("syncStatus", nz(e.syncStatus));
        m.put("bindStatus", e.bindStatus == null ? 0 : e.bindStatus);
        m.put("enabled", !Boolean.FALSE.equals(e.enabled));
        return m;
    }

    /** 区域名称（所在区域展示用）；未配置区域或未注入 mapper 时返回空串 */
    private String regionNameOf(String regionId) {
        if (regionId == null || regionId.isBlank() || regionMapper == null) {
            return "";
        }
        com.cqwlw.maintenance.entity.Region r = regionMapper.selectById(regionId);
        return r == null ? "" : nz(r.name);
    }

    private Map<String, Object> elevatorAdminRow(Elevator el) {
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", el.id);
        m.put("elevatorCode", nz(el.elevatorCode));
        m.put("elevatorName", nz(el.elevatorName));
        m.put("projectName", nz(el.projectName));
        m.put("location", nz(el.location));
        m.put("regCode", nz(el.regCode));
        // 注册代码：管理端「设备信息档案」对外字段名（用户口径）；regCode 保留给旧调用方
        m.put("registrationCode", nz(el.regCode));
        // 登记证编码（V58）：独立于注册代码的使用登记证编号
        m.put("certNo", nz(el.certNo));
        m.put("deviceCode", nz(el.deviceCode));
        // 区域与建档来源（Task 14 Step 2）：区域决定维保人员可见范围，来源区分 存量/移交/外接
        m.put("regionId", nz(el.regionId));
        m.put("regionName", regionNameOf(el.regionId));
        // 上传维保记录的必传项（前 5 项）缺失清单：管理端据此显示「待补全」
        m.put("missingRequired", DeviceProfileValidator.missingRequired(el));
        m.put("archiveSource", el.archiveSource == null ? 0 : el.archiveSource);
        m.put("insideNumber", nz(el.insideNumber));
        m.put("model", nz(el.model));
        m.put("factoryNumber", nz(el.factoryNumber));
        m.put("brand", nz(el.brand));
        m.put("manufacturer", nz(el.manufacturer));
        m.put("driveMode", nz(el.driveMode));
        m.put("ratedLoad", el.ratedLoad == null ? "" : String.valueOf(el.ratedLoad));
        m.put("ratedLoadUnit", nz(el.ratedLoadUnit));
        m.put("ratedSpeed", el.ratedSpeed == null ? "" : String.valueOf(el.ratedSpeed));
        m.put("ratedSpeedUnit", nz(el.ratedSpeedUnit));
        m.put("stationsDoors", nz(el.stationsDoors));
        m.put("useUnitEntityId", nz(el.useUnitEntityId));
        m.put("nextMaintenanceDate", TimeUtil.formatDate(el.nextMaintenanceDate));
        m.put("workerPlatformId", nz(el.workerPlatformId));
        m.put("assistantPlatformId", nz(el.assistantPlatformId));
        m.put("checkinThreshold", el.checkinThreshold == null ? "" : String.valueOf(el.checkinThreshold));
        m.put("lastMaintenanceAt", TimeUtil.format(el.lastMaintenanceAt));
        m.put("category", nz(el.category));
        m.put("useUnitId", nz(el.useUnitId));
        m.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        m.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
        m.put("inspectionReportFileId", nz(el.inspectionReportFileId));
        m.put("inspectionReportUrl", nz(el.inspectionReportUrl));
        m.put("inspectionReportUploadedAt", TimeUtil.format(el.inspectionReportUploadedAt));
        m.put("platformSyncedAt", TimeUtil.format(el.platformSyncedAt));
        m.put("lng", el.lng == null ? "" : String.valueOf(el.lng));
        m.put("lat", el.lat == null ? "" : String.valueOf(el.lat));
        // 位置待补高亮数据源（docs/09 决策 #4，docs/01 §3.4.1 降级策略）
        m.put("geoStatus", el.lng == null || el.lat == null ? "MISSING" : "OK");
        m.put("workerName", nz(el.workerName));
        m.put("workerPhone", nz(el.workerPhone));
        m.put("assistantName", nz(el.assistantName));
        m.put("elevatorAdminister", nz(el.elevatorAdminister));
        m.put("elevatorAdministerPhone", nz(el.elevatorAdministerPhone));
        m.put("emergencyPhone", nz(el.emergencyPhone));
        m.put("workTypeCode", nz(el.workTypeCode));
        m.put("intervalDays", el.intervalDays == null ? 0 : el.intervalDays);
        m.put("specialType", nz(el.specialType));
        m.put("status", nz(el.status));
        return m;
    }

    // ── 范围收敛新增（docs/04 V2.9 A.9.0 / docs/09 V3.3 §5.5·§6.6）──

    /** 批量导入经纬度（docs/09 V3.1/V3.3：位置待补补录入口），按 elevatorCode 更新坐标 */
    public Map<String, Object> batchGeo(Map<String, Object> body) {
        if (!(body.get("items") instanceof List<?> rawItems) || rawItems.isEmpty()) {
            throw new BizException(422, "items 不能为空");
        }
        List<Map<String, Object>> success = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object o : rawItems) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            String code = str(item, "code");
            String lng = str(item, "lng");
            String lat = str(item, "lat");
            try {
                if (code.isBlank() || lng.isBlank() || lat.isBlank()) {
                    throw new BizException(422, "code/lng/lat 均必填");
                }
                Elevator el = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                                .eq(Elevator::getElevatorCode, code)).stream().findFirst()
                        .orElseThrow(() -> new BizException(1404, "电梯不存在：" + code));
                el.lng = new BigDecimal(lng);
                el.lat = new BigDecimal(lat);
                elevatorMapper.updateById(el);
                success.add(Map.of("code", code));
            } catch (BizException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("code", code);
                f.put("codeNum", e.getCode());
                f.put("reason", e.getMessage());
                failed.add(f);
            } catch (NumberFormatException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("code", code);
                f.put("codeNum", 422);
                f.put("reason", "经纬度格式不正确");
                failed.add(f);
            }
        }
        return Map.of("success", success, "failed", failed);
    }

    /** 2.2 单主体同步：维保单位（docs/09 V3.1 ④，复用 /platform/sync 的 queryID 逻辑） */
    public Map<String, Object> syncCompanyEntityId() {
        Company c = companyMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new BizException(1404, "维保单位档案不存在"));
        return syncEntityId(c.organizationCode, c.name, () -> {
            Map<String, Object> entity = platformClient.queryEntity(c.organizationCode, c.name);
            c.entityId = String.valueOf(entity.get("entityID"));
            // 维保单位名称以平台 2.2 返回为准（2026-10-05 用户确认：本单位名称以平台登录侧获取为准）
            String platformName = entity.get("unitName") == null ? "" : String.valueOf(entity.get("unitName"));
            if (!platformName.isEmpty()) {
                c.name = platformName;
            }
            companyMapper.updateById(c);
            return c.entityId;
        });
    }

    /** 2.2 单主体同步：使用单位 */
    public Map<String, Object> syncUseUnitEntityId(String id) {
        UseUnit u = useUnitMapper.selectById(id);
        if (u == null) {
            throw new BizException(1404, "使用单位不存在：" + id);
        }
        if (u.organizationCode == null || u.organizationCode.isBlank()) {
            throw new BizException(422, "该使用单位未填统一社会信用代码，请先在档案中补填");
        }
        return syncEntityId(u.organizationCode, u.unitName, () -> {
            u.entityId = platformClient.queryEntityId(u.organizationCode, u.unitName);
            useUnitMapper.updateById(u);
            return u.entityId;
        });
    }

    private Map<String, Object> syncEntityId(String organizationCode, String unitName,
                                             java.util.concurrent.Callable<String> action) {
        if (organizationCode == null || organizationCode.isBlank()) {
            throw new BizException(422, "组织机构代码（organizationCode）为空，无法同步主体ID");
        }
        if (!platformClient.configured()) {
            return Map.of("found", false, "reason", "监管平台凭证未配置");
        }
        try {
            String entityId = action.call();
            if (entityId == null || entityId.isBlank()) {
                return Map.of("found", false, "reason", "平台未查询到该主体");
            }
            return Map.of("found", true, "entityId", entityId, "unitName", nz(unitName));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(2001, "平台 2.2 主体查询失败：" + e.getMessage());
        }
    }

    /**
     * 电梯贴梯二维码 PNG（docs/09 V3.3 §6.6）：内容=电梯编码字符串，
     * 小程序扫码后经 resolve-by-elevator / elevators/by-code 识别工单。
     */
    public byte[] elevatorQrPng(String elevatorId) {
        Elevator el = elevatorMapper.selectById(elevatorId);
        if (el == null) {
            throw new BizException(1404, "电梯不存在：" + elevatorId);
        }
        if (el.elevatorCode == null || el.elevatorCode.isBlank()) {
            throw new BizException(422, "该电梯未设置电梯编号，无法生成二维码");
        }
        try {
            var bits = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(el.elevatorCode, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300);
            var png = com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(bits);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(png, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "二维码生成失败：" + e.getMessage());
        }
    }

    private static void requireNoConflict(List<Map<String, Object>> conflicts) {
        if (conflicts != null && !conflicts.isEmpty()) {
            throw new BizException(1002, "手机号互斥冲突，请核对角色分配（docs/01 §3.2.4）",
                    Map.of("conflicts", conflicts));
        }
    }

    /** 角色中文名统一取 RoleCatalog（docs/14 §3），避免两处维护漂移 */
    private static String defaultRoleText(String role) {
        String name = RoleCatalog.nameOf(role);
        return name == null ? role : name;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
