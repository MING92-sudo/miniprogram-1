package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 2.7 按电梯出厂编号/注册代码/设备代码回填电梯编码/使用单位主体ID/安全管理员与应急电话（实测未脱敏，AGENTS §4.4），本地不一致以平台为准（手机号除外）； */
@Service
public class PlatformSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSyncService.class);

    private final CompanyMapper companyMapper;
    private final UseUnitMapper useUnitMapper;
    private final ElevatorMapper elevatorMapper;
    private final PlatformClient platformClient;
    private final PlatformTokenService tokenService;
    private final EmployeeMapper employeeMapper;
    private final PlatformReportService reportService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PlatformSyncService(CompanyMapper companyMapper, UseUnitMapper useUnitMapper,
                               ElevatorMapper elevatorMapper, PlatformClient platformClient,
                               PlatformTokenService tokenService, EmployeeMapper employeeMapper,
                               PlatformReportService reportService) {
        this.companyMapper = companyMapper;
        this.useUnitMapper = useUnitMapper;
        this.elevatorMapper = elevatorMapper;
        this.platformClient = platformClient;
        this.tokenService = tokenService;
        this.employeeMapper = employeeMapper;
        this.reportService = reportService;
    }

    public boolean configured() {
        return tokenService.configured();
    }

    public Map<String, Object> syncAll() {
        if (!tokenService.configured()) {
            throw new com.cqwlw.maintenance.common.BizException(2001, "监管平台凭证未配置（REG_* 环境变量）");
        }
        int entitySynced = syncEntities();
        int elevatorsSynced = syncElevators();
        // 2.5 的 workEndDate 为合同有效期下界，传滚动 +1 年会拿到空名单；与「平台登记人员」页一致不传
        List<Map<String, Object>> platformWorkers = platformClient.queryWorkList("0", null);
        int workersSynced = syncWorkers(platformWorkers);
        int workersCreated = autoCreateWorkers(platformWorkers);
        PlatformReportService.SyncLegacyResult legacy = reportService.syncLegacy();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entitySynced", entitySynced);
        summary.put("elevatorSynced", elevatorsSynced);
        summary.put("workerSynced", workersSynced);
        summary.put("workerCreated", workersCreated);
        summary.put("legacyUploaded", legacy.uploaded());
        summary.put("legacyStuck", legacy.stuck());
        summary.put("syncedAt", TimeUtil.format(TimeUtil.now()));
        return summary;
    }

    private int syncWorkers(List<Map<String, Object>> workers) {
        if (workers.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<>())) {
            if (e.certificate == null || e.certificate.isEmpty()) {
                continue;
            }
            for (Map<String, Object> w : workers) {
                if (e.certificate.equals(str(w.get("workManCertificate"))) && w.get("id") != null) {
                    e.platformId = String.valueOf(w.get("id"));
                    e.syncStatus = "SYNCED";
                    e.bindStatus = "1".equals(str(w.get("changState"))) ? 1 : 0;
                    employeeMapper.updateById(e);
                    n++;
                    break;
                }
            }
        }
        return n;
    }


    /** 用户需求①：2.4/平台侧登记后自动建档——2.5 名单中本地不存在（按证书号）的人员自动创建档案（随机初始密码） */
    private int autoCreateWorkers(List<Map<String, Object>> workers) {
        int created = 0;
        for (Map<String, Object> w : workers) {
            String cert = str(w.get("workManCertificate"));
            String pid = str(w.get("id"));
            if (cert == null || cert.isEmpty() || pid == null || pid.isEmpty()) {
                continue;
            }
            Long dup = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                    .eq(Employee::getCertificate, cert));
            if (dup != null && dup > 0) {
                continue;
            }
            Employee e = new Employee();
            e.id = Ids.next("emp");
            e.name = str(w.get("workManName"));
            e.phone = str(w.get("workManPhone"));
            e.account = e.phone;
            e.role = "WORKER";
            e.roleText = "维保人员";
            e.passwordHash = encoder.encode(randomPassword());
            e.platformId = pid;
            e.certificate = cert;
            e.workStartDate = millisToDate(w.get("workStartDate"));
            e.workEndDate = millisToDate(w.get("workEndDate"));
            e.workStat = str(w.get("workStat")).isEmpty() ? "normal" : str(w.get("workStat"));
            e.syncStatus = "SYNCED";
            e.enabled = true;
            employeeMapper.insert(e);
            created++;
        }
        return created;
    }

    private String millisToDate(Object millis) {
        try {
            return java.time.LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(Long.parseLong(String.valueOf(millis))),
                    com.cqwlw.maintenance.util.TimeUtil.ZONE).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
    /** 单单位 2.2 主体ID回填：单位名称+统一社会信用代码缺一即不查；平台未登记时 queryEntityId 抛 404。 */
    public boolean syncUseUnitEntityId(UseUnit u) {
        if (u.unitName == null || u.unitName.isBlank()
                || u.organizationCode == null || u.organizationCode.isBlank()) {
            return false;
        }
        u.entityId = platformClient.queryEntityId(u.organizationCode, u.unitName);
        useUnitMapper.updateById(u);
        return true;
    }

    private int syncEntities() {
        int n = 0;
        Company c = companyMapper.selectList(null).stream().findFirst().orElse(null);
        if (c != null && c.organizationCode != null && !c.organizationCode.isBlank()) {
            try {
                Map<String, Object> entity = platformClient.queryEntity(c.organizationCode, c.name);
                String entityId = entity.get("entityID") == null ? "" : String.valueOf(entity.get("entityID")).trim();
                if (!entityId.isEmpty()) {
                    c.entityId = entityId;
                    String platformName = entity.get("unitName") == null ? "" : String.valueOf(entity.get("unitName"));
                    if (!platformName.isEmpty()) {
                        c.name = platformName;
                    }
                    companyMapper.updateById(c);
                    n++;
                }
            } catch (Exception e) {
                log.warn("维保单位主体同步失败，继续后续同步: {}", e.getMessage());
            }
        }
        for (UseUnit u : useUnitMapper.selectList(null)) {
            try {
                if (syncUseUnitEntityId(u)) {
                    n++;
                }
            } catch (Exception e) {
                log.warn("使用单位主体同步失败，继续后续同步: {}", u.unitName);
            }
        }
        return n;
    }

    private int syncElevators() {
        int n = 0;
        for (Elevator el : elevatorMapper.selectList(new LambdaQueryWrapper<>())) {
            try {
                if (syncElevator(el)) {
                    n++;
                } else {
                    log.warn("电梯 2.7 同步跳过：无一条平台记录与本地标识匹配, code={}", el.elevatorCode);
                }
            } catch (Exception e) {
                log.warn("电梯 2.7 同步失败: code={}, {}", el.elevatorCode, e.getMessage());
            }
        }
        return n;
    }

    /** 单台 2.7 同步：按出厂编号/注册代码/设备代码查询，匹配成功才回填；返回是否命中平台记录。 */
    public boolean syncElevator(Elevator el) {
        Map<String, String> cond = new LinkedHashMap<>();
        cond.put("factoryNumber", el.factoryNumber);
        cond.put("registrationCode", el.regCode);
        cond.put("deviceCode", el.deviceCode);
        List<Map<String, Object>> list = platformClient.queryElevatorInfo(cond);
        Map<String, Object> p = matchRecord(cond, list);
        if (p == null) {
            return false;
        }
        if (p.get("elevatorCode") != null) {
            el.elevatorCode = String.valueOf(p.get("elevatorCode"));
        }
        // 使用单位主体ID实测键名为 useUnitEntityID（大写 ID），规范示例误作 useUnitEntityId，兼容两种拼写
        String platformUnitEntityId = firstText(p, "useUnitEntityID", "useUnitEntityId");
        if (!platformUnitEntityId.isEmpty()) {
            el.useUnitEntityId = platformUnitEntityId;
        }
        if (hasRealValue(str(p.get("elevatorAdministerPhone")))) {
            el.elevatorAdministerPhone = str(p.get("elevatorAdministerPhone"));
        }
        if (hasRealValue(str(p.get("emergencyPhone")))) {
            el.emergencyPhone = str(p.get("emergencyPhone"));
        }
        if (hasRealValue(str(p.get("elevatorAdminister")))) {
            el.elevatorAdminister = str(p.get("elevatorAdminister"));
        }
        // 未手动指定使用单位时，按平台主体ID挂接本地使用单位档案
        if ((el.useUnitId == null || el.useUnitId.isBlank())
                && el.useUnitEntityId != null && !el.useUnitEntityId.isBlank()) {
            UseUnit uu = useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>()
                    .eq(UseUnit::getEntityId, el.useUnitEntityId)).stream().findFirst().orElse(null);
            if (uu != null) {
                el.useUnitId = uu.id;
            }
        }
        el.platformSyncedAt = TimeUtil.now();
        elevatorMapper.updateById(el);
        return true;
    }

    /** 平台 2.7 会回显这些标识字段，故可据此确认归属； */
    private static Map<String, Object> matchRecord(Map<String, String> cond,
                                                   List<Map<String, Object>> list) {
        for (Map<String, Object> p : list) {
            if (matchesQuery(cond, p)) {
                return p;
            }
        }
        return null;
    }

    /** 2.7 记录是否命中查询条件（任一同值即命中；查询条件为空的项不参与比较） */
    public static boolean matchesQuery(Map<String, String> cond, Map<String, Object> record) {
        if (record == null) {
            return false;
        }
        return sameValue(cond.get("factoryNumber"), record.get("factoryNumber"))
                || sameValue(cond.get("registrationCode"), record.get("registrationCode"))
                || sameValue(cond.get("deviceCode"), record.get("deviceCode"))
                || sameValue(cond.get("elevatorCode"), record.get("elevatorCode"));
    }

    private static boolean sameValue(String local, Object remote) {
        if (local == null || local.trim().isEmpty() || remote == null) {
            return false;
        }
        return local.trim().equalsIgnoreCase(String.valueOf(remote).trim());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 按优先级取第一个非空键的文本值（平台字段拼写兼容用） */
    private static String firstText(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    /** 2.7 返回的"null"/空串/脱敏值均视为平台未登记，不覆盖本地档案字段 */
    private static boolean hasRealValue(String v) {
        return v != null && !v.isBlank() && !v.contains("*")
                && !"null".equalsIgnoreCase(v) && !"undefined".equalsIgnoreCase(v);
    }
}
