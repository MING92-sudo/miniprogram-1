package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminArchiveService(CompanyMapper companyMapper, UseUnitMapper useUnitMapper,
                               EmployeeMapper employeeMapper, ElevatorMapper elevatorMapper,
                               PhoneMutexService phoneMutexService) {
        this.companyMapper = companyMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.elevatorMapper = elevatorMapper;
        this.phoneMutexService = phoneMutexService;
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
        e.passwordHash = encoder.encode(str(body, "password").isBlank() ? "123456" : str(body, "password"));
        e.platformId = str(body, "platformId");
        e.certificate = str(body, "certificate");
        e.workStartDate = str(body, "workStartDate");
        e.workEndDate = str(body, "workEndDate");
        e.workStat = str(body, "workStat").isBlank() ? "normal" : str(body, "workStat");
        e.syncStatus = str(body, "syncStatus").isBlank() ? "NOT_SYNCED" : str(body, "syncStatus");
        employeeMapper.insert(e);
        return employeeRow(e);
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
        return elevatorAdminRow(el);
    }

    // ── 私有 ──

    private void applyUseUnit(UseUnit u, Map<String, Object> body) {
        if (body.get("unitName") != null) {
            u.unitName = str(body, "unitName");
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
        if (body.get("workerName") != null) {
            el.workerName = str(body, "workerName");
        }
        if (body.get("workerPhone") != null) {
            el.workerPhone = str(body, "workerPhone");
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
        m.put("platformId", nz(e.platformId));
        m.put("certificate", nz(e.certificate));
        m.put("workStartDate", nz(e.workStartDate));
        m.put("workEndDate", nz(e.workEndDate));
        m.put("workStat", nz(e.workStat));
        m.put("syncStatus", nz(e.syncStatus));
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
        m.put("category", nz(el.category));
        m.put("useUnitId", nz(el.useUnitId));
        m.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        m.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
        m.put("platformSyncedAt", TimeUtil.format(el.platformSyncedAt));
        m.put("lng", el.lng == null ? "" : String.valueOf(el.lng));
        m.put("lat", el.lat == null ? "" : String.valueOf(el.lat));
        // 位置待补高亮数据源（docs/09 决策 #4，docs/01 §3.4.1 降级策略）
        m.put("geoStatus", el.lng == null || el.lat == null ? "MISSING" : "OK");
        m.put("workerName", nz(el.workerName));
        m.put("workerPhone", nz(el.workerPhone));
        m.put("assistantName", nz(el.assistantName));
        m.put("elevatorAdminister", nz(el.elevatorAdminister));
        m.put("emergencyPhone", nz(el.emergencyPhone));
        m.put("workTypeCode", nz(el.workTypeCode));
        m.put("intervalDays", el.intervalDays == null ? 0 : el.intervalDays);
        m.put("specialType", nz(el.specialType));
        return m;
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
