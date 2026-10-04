package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.auth.AdminRoles;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ElevatorMapper elevatorMapper;
    private final PhoneMutexService phoneMutexService;
    private final WorkOrderMapper workOrderMapper;
    private final PlatformClient platformClient;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminArchiveService(CompanyMapper companyMapper, UseUnitMapper useUnitMapper,
                               EmployeeMapper employeeMapper, ElevatorMapper elevatorMapper,
                               PhoneMutexService phoneMutexService,
                               WorkOrderMapper workOrderMapper, PlatformClient platformClient) {
        this.companyMapper = companyMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.elevatorMapper = elevatorMapper;
        this.phoneMutexService = phoneMutexService;
        this.workOrderMapper = workOrderMapper;
        this.platformClient = platformClient;
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

    public List<Map<String, Object>> employeeList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .orderByAsc(Employee::getId))) {
            list.add(employeeRow(e));
        }
        return list;
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
            throw new BizException(422, "角色仅允许 WORKER/LEADER/ADMIN/SYS_ADMIN");
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
        e.roleText = str(body, "roleText").isBlank() ? defaultRoleText(role) : str(body, "roleText");
        e.groupName = str(body, "groupName");
        String initialPassword = str(body, "password").isBlank() ? randomPassword() : str(body, "password");
        e.passwordHash = encoder.encode(initialPassword);
        e.platformId = str(body, "platformId");
        e.certificate = str(body, "certificate");
        e.workStartDate = str(body, "workStartDate");
        e.workEndDate = str(body, "workEndDate");
        e.workStat = str(body, "workStat").isBlank() ? "normal" : str(body, "workStat");
        e.syncStatus = str(body, "syncStatus").isBlank() ? "NOT_SYNCED" : str(body, "syncStatus");
        e.enabled = true;
        employeeMapper.insert(e);
        Map<String, Object> out = employeeRow(e);
        out.put("initialPassword", initialPassword);
        return out;
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
        useUnitMapper.deleteById(id);
        return Map.of("ok", true);
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

    public Map<String, Object> updateEmployee(String id, Map<String, Object> body) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, "人员不存在");
        }
        if (body.get("name") != null) {
            e.name = str(body, "name");
        }
        if (body.get("phone") != null) {
            e.phone = str(body, "phone");
        }
        if (body.get("role") != null) {
            if (!AdminRoles.assignable(str(body, "role"))) {
                throw new BizException(422, "角色仅允许 WORKER/LEADER/ADMIN/SYS_ADMIN");
            }
            e.role = str(body, "role");
            e.roleText = str(body, "roleText").isBlank() ? defaultRoleText(e.role) : str(body, "roleText");
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
            e.groupName = str(body, "groupName");
        }
        if (!str(body, "password").isBlank()) {
            e.passwordHash = encoder.encode(str(body, "password"));
        }
        requireNoConflict(phoneMutexService.checkEmployee(e.id, e.role, e.phone));
        employeeMapper.updateById(e);
        return employeeRow(e);
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
        if (str(body, "elevatorCode").isBlank() || str(body, "elevatorName").isBlank()) {
            throw new BizException(422, "电梯编号与名称必填");
        }
        Elevator el = new Elevator();
        el.id = Ids.next("el");
        applyElevator(el, body);
        requireNoConflict(phoneMutexService.checkElevator(el.workerPhone));
        elevatorMapper.insert(el);
        return elevatorAdminRow(el);
    }

    public Map<String, Object> updateElevator(String id, Map<String, Object> body) {
        Elevator el = elevatorMapper.selectById(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        applyElevator(el, body);
        requireNoConflict(phoneMutexService.checkElevator(el.workerPhone));
        elevatorMapper.updateById(el);
        // 经纬度清空（留空保存）必须显式置 NULL——updateById 默认跳过 null 字段，
        // 否则错误的首采坐标永远无法清除（清空后下次签到重新现场采集，docs/02 §5.4）
        boolean clearLng = body.get("lng") != null && str(body, "lng").isBlank();
        boolean clearLat = body.get("lat") != null && str(body, "lat").isBlank();
        if (clearLng || clearLat) {
            LambdaUpdateWrapper<Elevator> uw = new LambdaUpdateWrapper<Elevator>().eq(Elevator::getId, id);
            if (clearLng) {
                uw.set(Elevator::getLng, null);
            }
            if (clearLat) {
                uw.set(Elevator::getLat, null);
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

    private void applyElevator(Elevator el, Map<String, Object> body) {
        if (body.get("elevatorCode") != null) {
            el.elevatorCode = str(body, "elevatorCode");
        }
        if (body.get("elevatorName") != null) {
            el.elevatorName = str(body, "elevatorName");
        }
        if (body.get("location") != null) {
            el.location = str(body, "location");
        }
        if (body.get("regCode") != null) {
            el.regCode = str(body, "regCode");
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
        if (body.get("factoryNumber") != null) {
            el.factoryNumber = str(body, "factoryNumber");
        }
        if (body.get("brand") != null) {
            el.brand = str(body, "brand");
        }
        if (body.get("manufacturer") != null) {
            el.manufacturer = str(body, "manufacturer");
        }
        if (body.get("productNo") != null) {
            el.productNo = str(body, "productNo");
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
        if (body.get("workerName") != null) {
            el.workerName = str(body, "workerName");
        }
        if (body.get("workerPhone") != null) {
            el.workerPhone = str(body, "workerPhone");
        }
        if (body.get("workerPlatformId") != null) {
            el.workerPlatformId = str(body, "workerPlatformId");
        }
        if (body.get("assistantName") != null) {
            el.assistantName = str(body, "assistantName");
        }
        if (body.get("assistantPlatformId") != null) {
            el.assistantPlatformId = str(body, "assistantPlatformId");
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
            // 停用=INACTIVE；空/ACTIVE=恢复在保（docs/09 §6.6：停用后不派单、小程序不可见）
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
        m.put("groupName", nz(e.groupName));
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

    private Map<String, Object> elevatorAdminRow(Elevator el) {
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", el.id);
        m.put("elevatorCode", nz(el.elevatorCode));
        m.put("elevatorName", nz(el.elevatorName));
        m.put("location", nz(el.location));
        m.put("regCode", nz(el.regCode));
        m.put("deviceCode", nz(el.deviceCode));
        m.put("insideNumber", nz(el.insideNumber));
        m.put("model", nz(el.model));
        m.put("factoryNumber", nz(el.factoryNumber));
        m.put("brand", nz(el.brand));
        m.put("manufacturer", nz(el.manufacturer));
        m.put("productNo", nz(el.productNo));
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

    /**
     * 批量绑定维保人员与电梯（docs/04 A.9.0）：自动派单的数据前提。
     * 逐台校验 1004（platform_id 未同步）/1002（手机互斥）/1007（证件有效期、主配同一人），
     * 单台失败不阻塞其余成功台；返回 success/failed 逐台明细。
     */
    public Map<String, Object> batchAssignWorkers(Map<String, Object> body) {
        if (!(body.get("elevatorIds") instanceof List<?> rawIds) || rawIds.isEmpty()) {
            throw new BizException(422, "elevatorIds 不能为空");
        }
        if (rawIds.size() > 200) {
            throw new BizException(422, "单次最多绑定 200 台电梯");
        }
        String principalId = str(body, "principalId");
        if (principalId.isBlank()) {
            throw new BizException(422, "principalId（维保人员1/主）必填");
        }
        String assistantId = str(body, "assistantId");
        Employee principal = requireEmployee(principalId, "维保人员1");
        Employee assistant = assistantId.isBlank() ? null : requireEmployee(assistantId, "维保人员2");
        if (assistant != null && assistant.id.equals(principal.id)) {
            throw new BizException(1007, "主/配合人员不可为同一人（docs/01 §3.2.4 ④）");
        }
        // 人员级校验一次（1004/1007），逐台复用
        String personError = bindingError(principal);
        if (personError == null && assistant != null) {
            personError = bindingError(assistant);
        }
        // 手机互斥一次（同一主/配人员绑多台电梯时冲突结论相同）
        List<Map<String, Object>> mutexConflicts = phoneMutexService.checkElevator(principal.phone);
        List<Map<String, Object>> success = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object o : rawIds) {
            String elevatorId = String.valueOf(o);
            try {
                if (personError != null) {
                    throw new BizException(1004, personError);
                }
                if (!mutexConflicts.isEmpty()) {
                    throw new BizException(1002, "手机号互斥冲突，请核对角色分配（docs/01 §3.2.4）",
                            Map.of("conflicts", mutexConflicts));
                }
                Elevator el = elevatorMapper.selectById(elevatorId);
                if (el == null) {
                    throw new BizException(1404, "电梯不存在：" + elevatorId);
                }
                if ("INACTIVE".equals(el.status)) {
                    throw new BizException(422, "电梯已停用：" + nz(el.elevatorName));
                }
                el.workerName = nz(principal.name);
                el.workerPhone = nz(principal.phone);
                el.workerPlatformId = nz(principal.platformId);
                if (assistant != null) {
                    el.assistantName = nz(assistant.name);
                    el.assistantPlatformId = nz(assistant.platformId);
                }
                elevatorMapper.updateById(el);
                success.add(Map.of("elevatorId", elevatorId,
                        "elevatorName", nz(el.elevatorName)));
            } catch (BizException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("elevatorId", elevatorId);
                f.put("code", e.getCode());
                f.put("reason", e.getMessage());
                if (e.getData() != null) {
                    f.put("data", e.getData());
                }
                failed.add(f);
            }
        }
        return Map.of("success", success, "failed", failed);
    }

    /** 人员绑定级校验：1004 platform_id 未同步不可派工；1007 证件过期（docs/04 A.0 码表） */
    private String bindingError(Employee e) {
        if (e.platformId == null || e.platformId.isBlank()) {
            return "人员 platform_id 未同步（1004）：" + nz(e.name) + "，请先完成 2.4 登记/2.5 同步";
        }
        if (e.workEndDate != null && !e.workEndDate.isBlank()) {
            try {
                java.time.LocalDate end = java.time.LocalDate.parse(e.workEndDate);
                if (end.isBefore(TimeUtil.now().toLocalDate())) {
                    return "证件已过期（1007）：" + nz(e.name) + "（" + e.workEndDate + "）";
                }
            } catch (Exception ignored) {
                // 日期格式异常不做拦截（与既有档案口径一致）
            }
        }
        return null;
    }

    private Employee requireEmployee(String id, String label) {
        Employee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException(1404, label + "不存在：" + id);
        }
        return e;
    }

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
            c.entityId = platformClient.queryEntityId(c.organizationCode, c.name);
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

    private static String defaultRoleText(String role) {
        return switch (role) {
            case AdminRoles.WORKER -> "维保人员";
            case AdminRoles.LEADER -> "班组长";
            case AdminRoles.ADMIN -> "维保部管理员";
            case AdminRoles.SYS_ADMIN -> "系统管理员";
            default -> role;
        };
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
