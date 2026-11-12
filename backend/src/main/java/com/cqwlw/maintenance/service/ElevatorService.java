package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 电梯档案视图（docs/01 §3.4.1 + 平台 2.7 回填字段，字段名与前端契约一致）。
 */
@Service
public class ElevatorService {

    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeScopeService scopeService;

    public ElevatorService(ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper,
                           EmployeeScopeService scopeService) {
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.scopeService = scopeService;
    }

    public Elevator get(String id) {
        return elevatorMapper.selectById(id);
    }

    public Elevator getByCode(String code) {
        return elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, code).last("LIMIT 1"));
    }

    public List<Map<String, Object>> listView(String empId) {
        // 读范围（Task 8）：本区域电梯 + 本班组（组长）+ 本人；区域未分区时退化为原来的本人口径。
        // 写路径（project-name/local）仍走 visibleWorkerPlatformIds 严格谓词，不得共用。
        Set<String> ids = scopeService.visibleReadPlatformIds(scopeService.require(empId));
        return elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>().orderByAsc(Elevator::getId))
                .stream()
                // 停用（INACTIVE）电梯小程序不可见（docs/09 §6.6 / V8 迁移注释）；status 多为 NULL，故在内存过滤
                .filter(el -> !"INACTIVE".equals(el.status))
                // 单人作业时 assistantPlatformId 为空；Set.of(...) 的 contains(null) 会抛 NPE，须判空
                .filter(el -> ids == null
                        || (el.workerPlatformId != null && ids.contains(el.workerPlatformId))
                        || (el.assistantPlatformId != null && ids.contains(el.assistantPlatformId)))
                .map(el -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", el.id);
                    m.put("elevatorName", el.elevatorName);
                    m.put("elevatorCode", el.elevatorCode);
                    m.put("deviceCode", el.deviceCode);
                    m.put("regCode", el.regCode);
                    m.put("model", el.model);
                    // 项目名称 = 小区/楼盘名称（elevator.project_name），与使用单位名称解耦（docs/01 §3.4.1）
                    m.put("projectName", nz(el.projectName));
                    return m;
                }).toList();
    }

    /** 扫码回填电梯信息（急修单用，docs/04 A.6） */
    public Map<String, Object> viewByCode(String code) {
        Elevator el = getByCode(code == null ? "" : code.trim());
        if (el == null) {
            throw new BizException(1404, "电梯不存在，请核对编号");
        }
        if ("INACTIVE".equals(el.status)) {
            throw new BizException(422, "该电梯已停用，贴梯二维码失效（docs/09 §6.6）");
        }
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", el.id);
        m.put("elevatorCode", el.elevatorCode);
        m.put("elevatorName", el.elevatorName);
        m.put("location", nz(el.location));
        m.put("regCode", nz(el.regCode));
        m.put("deviceCode", nz(el.deviceCode));
        m.put("model", nz(el.model));
        m.put("category", nz(el.category));
        m.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        return m;
    }

    public Map<String, Object> profile(String id, String empId) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        // 越权防护：仅可查看本人（或本组）负责的电梯档案，防跨班组读取管理员/应急电话等敏感字段
        Set<String> ids = scopeService.visibleWorkerPlatformIds(scopeService.require(empId));
        if (ids != null
                && (el.workerPlatformId == null || !ids.contains(el.workerPlatformId))
                && (el.assistantPlatformId == null || !ids.contains(el.assistantPlatformId))) {
            throw new BizException(1403, "仅可查看本人负责的电梯");
        }
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("syncedAt", TimeUtil.format(el.platformSyncedAt));
        platform.put("elevatorCode", nz(el.elevatorCode));
        platform.put("registrationCode", nz(el.regCode));
        platform.put("deviceCode", nz(el.deviceCode));
        platform.put("factoryNumber", nz(el.factoryNumber));
        platform.put("useUnitEntityId", nz(el.useUnitEntityId));
        platform.put("elevatorAdminister", nz(el.elevatorAdminister));
        platform.put("elevatorAdministerPhone", nz(el.elevatorAdministerPhone));
        platform.put("emergencyPhone", nz(el.emergencyPhone));

        Map<String, Object> local = new LinkedHashMap<>();
        local.put("projectName", nz(el.projectName));
        local.put("unitPrincipal", uu == null ? "" : nz(uu.unitPrincipal));
        local.put("address", nz(el.location));
        local.put("lng", el.lng != null ? String.valueOf(el.lng) : "");
        local.put("lat", el.lat != null ? String.valueOf(el.lat) : "");
        local.put("brand", nz(el.brand));
        local.put("manufacturer", nz(el.manufacturer));
        local.put("productNo", nz(el.productNo));
        local.put("driveMode", nz(el.driveMode));
        local.put("ratedLoad", el.ratedLoad != null ? el.ratedLoad + (el.ratedLoadUnit == null ? "kg" : el.ratedLoadUnit) : "");
        local.put("ratedSpeed", el.ratedSpeed != null ? el.ratedSpeed + (el.ratedSpeedUnit == null ? "m/s" : el.ratedSpeedUnit) : "");
        local.put("stationsDoors", nz(el.stationsDoors));
        local.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
        local.put("nextMaintenanceDate", TimeUtil.formatDate(el.nextMaintenanceDate));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elevatorId", el.id);
        out.put("elevatorName", el.elevatorName);
        out.put("category", nz(el.category));
        out.put("insideNumber", nz(el.insideNumber));
        out.put("model", nz(el.model));
        out.put("platform", platform);
        out.put("local", local);
        return out;
    }

    /** 维保人员现场更正项目名称（小区名称，管理端可能写错；docs/01 §3.4.1）。仅本人负责的电梯，越权 1403。 */
    public void updateProjectName(String id, String empId, String projectName) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        Set<String> ids = scopeService.visibleWorkerPlatformIds(scopeService.require(empId));
        if (ids != null
                && (el.workerPlatformId == null || !ids.contains(el.workerPlatformId))
                && (el.assistantPlatformId == null || !ids.contains(el.assistantPlatformId))) {
            throw new BizException(1403, "仅可维护本人负责的电梯");
        }
        String v = projectName == null ? "" : projectName.trim();
        if (v.isEmpty()) {
            throw new BizException(422, "项目名称不能为空");
        }
        Elevator patch = new Elevator();
        patch.id = el.id;
        patch.projectName = v;
        elevatorMapper.updateById(patch);
    }

    /** 维保人员现场维护设备本地信息（docs/01 §3.4.1）。仅本人负责的电梯，越权 1403。
     *  可改：项目名称/安装地址/品牌/制造单位/产品编号/驱动方式/额定载重/额定速度/层站门/下次检验、维保日期。
     *  不开放：经纬度（签到围栏，严禁伪造坐标）、使用单位负责人（属使用单位档案，非设备信息）。
     *  项目名称变更时同步 elevatorName（电梯名称 = 项目名称，合并，用户需求 2026-11-11）。 */
    public void updateLocal(String id, String empId, Map<String, Object> body) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        Set<String> ids = scopeService.visibleWorkerPlatformIds(scopeService.require(empId));
        if (ids != null
                && (el.workerPlatformId == null || !ids.contains(el.workerPlatformId))
                && (el.assistantPlatformId == null || !ids.contains(el.assistantPlatformId))) {
            throw new BizException(1403, "仅可维护本人负责的电梯");
        }

        LambdaUpdateWrapper<Elevator> uw = new LambdaUpdateWrapper<Elevator>().eq(Elevator::getId, id);
        boolean any = false;
        if (body != null && body.containsKey("projectName")) {
            String v = str(body, "projectName").trim();
            if (v.isEmpty()) {
                throw new BizException(422, "项目名称不能为空");
            }
            // 电梯名称 = 项目名称（小区），合并维护（docs/01 §3.4.1）
            uw.set(Elevator::getProjectName, v).set(Elevator::getElevatorName, v);
            any = true;
        }
        if (body != null && body.containsKey("address")) {
            uw.set(Elevator::getLocation, str(body, "address"));
            any = true;
        }
        if (body != null && body.containsKey("brand")) {
            uw.set(Elevator::getBrand, str(body, "brand"));
            any = true;
        }
        if (body != null && body.containsKey("manufacturer")) {
            uw.set(Elevator::getManufacturer, str(body, "manufacturer"));
            any = true;
        }
        if (body != null && body.containsKey("productNo")) {
            uw.set(Elevator::getProductNo, str(body, "productNo"));
            any = true;
        }
        if (body != null && body.containsKey("driveMode")) {
            uw.set(Elevator::getDriveMode, str(body, "driveMode"));
            any = true;
        }
        if (body != null && body.containsKey("ratedLoad")) {
            uw.set(Elevator::getRatedLoad, intOrNull(str(body, "ratedLoad")));
            any = true;
        }
        if (body != null && body.containsKey("ratedLoadUnit")) {
            uw.set(Elevator::getRatedLoadUnit, str(body, "ratedLoadUnit"));
            any = true;
        }
        if (body != null && body.containsKey("ratedSpeed")) {
            String s = str(body, "ratedSpeed");
            uw.set(Elevator::getRatedSpeed, s.isBlank() ? null : decimalOrNull(s));
            any = true;
        }
        if (body != null && body.containsKey("ratedSpeedUnit")) {
            uw.set(Elevator::getRatedSpeedUnit, str(body, "ratedSpeedUnit"));
            any = true;
        }
        if (body != null && body.containsKey("stationsDoors")) {
            uw.set(Elevator::getStationsDoors, str(body, "stationsDoors"));
            any = true;
        }
        if (body != null && body.containsKey("nextCheckDate")) {
            uw.set(Elevator::getNextCheckDate, TimeUtil.parseDate(str(body, "nextCheckDate")));
            any = true;
        }
        if (body != null && body.containsKey("nextMaintenanceDate")) {
            uw.set(Elevator::getNextMaintenanceDate, TimeUtil.parseDate(str(body, "nextMaintenanceDate")));
            any = true;
        }
        if (!any) {
            throw new BizException(422, "无可更新的设备信息");
        }
        elevatorMapper.update(null, uw);
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定载重必须为整数");
        }
    }

    private static BigDecimal decimalOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定速度必须为数字");
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
