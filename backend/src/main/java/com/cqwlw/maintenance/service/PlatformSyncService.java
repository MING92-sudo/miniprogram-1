package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台档案同步（P2 验收：2.2/2.7 从平台拉回真实数据）：
 * 2.2 对维保单位与各使用单位查 entityID 落库；2.7 按电梯出厂编号/注册代码/设备代码/elevatorCode
 * 回填电梯编码/使用单位主体ID/安全管理员与应急电话（实测未脱敏，AGENTS §4.4），本地不一致以平台为准（手机号除外）。
 */
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

    public Map<String, Object> syncAll() {
        if (!tokenService.configured()) {
            throw new com.cqwlw.maintenance.common.BizException(2001, "监管平台凭证未配置（REG_* 环境变量）");
        }
        int entitySynced = syncEntities();
        int elevatorsSynced = syncElevators();
        int workersSynced = syncWorkers();
        int legacyUploaded = reportService.syncLegacy();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entitySynced", entitySynced);
        summary.put("elevatorSynced", elevatorsSynced);
        summary.put("workerSynced", workersSynced);
        summary.put("legacyUploaded", legacyUploaded);
        summary.put("syncedAt", TimeUtil.format(TimeUtil.now()));
        return summary;
    }

    /**
     * 2.5 人员 platform_id 同步（docs/04 B.4：按证书号轮询精确匹配回填），
     * 随 /platform/sync 一并触发；单人失败不影响其余人员。
     */
    private int syncWorkers() {
        String end = TimeUtil.date(TimeUtil.now().plusYears(1));
        List<Map<String, Object>> workers = platformClient.queryWorkList("0", end);
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
                    employeeMapper.updateById(e);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private int syncEntities() {
        int n = 0;
        Company c = companyMapper.selectList(null).stream().findFirst().orElse(null);
        if (c != null && c.organizationCode != null) {
            c.entityId = platformClient.queryEntityId(c.organizationCode, c.name);
            companyMapper.updateById(c);
            n++;
        }
        for (UseUnit u : useUnitMapper.selectList(null)) {
            if (u.unitName != null && c != null && c.organizationCode != null) {
                u.entityId = platformClient.queryEntityId(c.organizationCode, u.unitName);
                useUnitMapper.updateById(u);
                n++;
            }
        }
        return n;
    }

    private int syncElevators() {
        int n = 0;
        for (Elevator el : elevatorMapper.selectList(new LambdaQueryWrapper<>())) {
            try {
                Map<String, String> cond = new LinkedHashMap<>();
                // AGENTS §4.4：实测平台支持按 elevatorCode 查询（docs/06 #8 待平台书面确认）
                cond.put("elevatorCode", el.elevatorCode);
                cond.put("factoryNumber", el.factoryNumber);
                cond.put("registrationCode", el.regCode);
                cond.put("deviceCode", el.deviceCode);
                List<Map<String, Object>> list = platformClient.queryElevatorInfo(cond);
                Map<String, Object> p = matchRecord(cond, list);
                if (p == null) {
                    // fail-closed：无法确认返回记录就是本梯时一律不回写。原实现盲取 list.get(0)，
                    // 平台返回他梯记录时会把他的 elevatorCode 写成本梯的，而该字段是 2.7 查询
                    // 与派单的身份键，一旦写错后续同步与派单都会落到别的梯上
                    log.warn("电梯 2.7 同步跳过：返回 {} 条但无一条与本地标识匹配, code={}",
                            list.size(), el.elevatorCode);
                    continue;
                }
                if (p.get("elevatorCode") != null) {
                    el.elevatorCode = String.valueOf(p.get("elevatorCode"));
                }
                if (p.get("useUnitEntityId") != null) {
                    el.useUnitEntityId = String.valueOf(p.get("useUnitEntityId"));
                }
                // 手机号脱敏值不覆盖本地真实号码（docs/04 B.7：以平台为准、手机号除外）
                if (isUnmasked(str(p.get("elevatorAdministerPhone")))) {
                    el.elevatorAdministerPhone = str(p.get("elevatorAdministerPhone"));
                }
                if (isUnmasked(str(p.get("emergencyPhone")))) {
                    el.emergencyPhone = str(p.get("emergencyPhone"));
                }
                if (p.get("elevatorAdminister") != null) {
                    el.elevatorAdminister = str(p.get("elevatorAdminister"));
                }
                el.platformSyncedAt = TimeUtil.now();
                elevatorMapper.updateById(el);
                n++;
            } catch (Exception e) {
                log.warn("电梯 2.7 同步失败: code={}, {}", el.elevatorCode, e.getMessage());
            }
        }
        return n;
    }

    /**
     * 从平台返回中挑出能确认是本梯的记录：出厂编号/注册代码/设备代码/电梯编码任一非空且相等。
     * 平台 2.7 会回显这些标识字段（docs/04 B.7 实测样例），故可据此确认归属；
     * 四项本地标识全为空时无法确认，返回 null 由调用方跳过。
     */
    private static Map<String, Object> matchRecord(Map<String, String> cond,
                                                   List<Map<String, Object>> list) {
        for (Map<String, Object> p : list) {
            if (sameValue(cond.get("factoryNumber"), p.get("factoryNumber"))
                    || sameValue(cond.get("registrationCode"), p.get("registrationCode"))
                    || sameValue(cond.get("deviceCode"), p.get("deviceCode"))
                    || sameValue(cond.get("elevatorCode"), p.get("elevatorCode"))) {
                return p;
            }
        }
        return null;
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

    private static boolean isUnmasked(String phone) {
        return phone != null && !phone.contains("*");
    }
}
