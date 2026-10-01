package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 电梯档案视图：字段名与前端契约一致。 */
@Service
public class ElevatorService {

    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;

    public ElevatorService(ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper) {
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
    }

    public Elevator get(String id) {
        return elevatorMapper.selectById(id);
    }

    public Elevator getByCode(String code) {
        return elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, code).last("LIMIT 1"));
    }

    public List<Map<String, Object>> listView() {
        return elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>().orderByAsc(Elevator::getId))
                .stream().map(el -> {
                    UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", el.id);
                    m.put("elevatorName", el.elevatorName);
                    m.put("elevatorCode", el.elevatorCode);
                    m.put("deviceCode", el.deviceCode);
                    m.put("regCode", el.regCode);
                    m.put("model", el.model);
                    m.put("projectName", uu == null ? "" : uu.unitName);
                    return m;
                }).toList();
    }

    public Map<String, Object> profile(String id) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
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
        local.put("projectName", uu == null ? "" : nz(uu.unitName));
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

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
