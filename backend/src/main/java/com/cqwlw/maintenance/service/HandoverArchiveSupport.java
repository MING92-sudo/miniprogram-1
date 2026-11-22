package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 移交归档支撑：起草单位选项与后续 2.7 平台身份回填边界。 */
@Service
public class HandoverArchiveSupport {

    private final UseUnitMapper useUnitMapper;
    private final ElevatorMapper elevatorMapper;
    private final PlatformClient platformClient;

    public HandoverArchiveSupport(UseUnitMapper useUnitMapper, ElevatorMapper elevatorMapper,
                                  PlatformClient platformClient) {
        this.useUnitMapper = useUnitMapper;
        this.elevatorMapper = elevatorMapper;
        this.platformClient = platformClient;
    }

    public void requireUseUnit(String useUnitId) {
        if (useUnitId == null || useUnitId.isBlank() || useUnitMapper.selectById(useUnitId) == null) {
            throw new BizException(422, "使用单位不存在，请先在使用单位档案中建档");
        }
    }

    public List<Map<String, String>> useUnitOptions() {
        return useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>().orderByAsc(UseUnit::getUnitName))
                .stream().map(u -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", u.id);
                    row.put("unitName", u.unitName);
                    return row;
                }).toList();
    }

    /** 归档前补齐平台身份；任何不确定、冲突或重复都在电梯 insert 与首保排期前失败。 */
    public void enrich(HandoverOrder order, Elevator elevator) {
        if (text(order.factoryNumber).isEmpty()) {
            throw new BizException(422, "出厂编号为 2.7 查询及设备档案必传项，不能归档");
        }
        if (!text(order.useUnitId).isEmpty()) {
            requireUseUnit(order.useUnitId);
        }
        if (text(order.elevatorCode).isEmpty() || text(order.regCode).isEmpty() || text(order.deviceCode).isEmpty()) {
            Map<String, String> conditions = new LinkedHashMap<>();
            conditions.put("factoryNumber", text(order.factoryNumber));
            conditions.put("registrationCode", text(order.regCode));
            conditions.put("deviceCode", text(order.deviceCode));
            List<Map<String, Object>> raw = platformClient.queryElevatorInfo(conditions);
            if (raw.size() != 1) {
                throw new BizException(422, "2.7 返回记录数不是 1（实际 " + raw.size()
                        + " 条），请确认注册登记完成后重试");
            }
            Map<String, Object> platform = raw.get(0);
            if (!matches(conditions, platform)) {
                throw new BizException(422, "2.7 返回记录与本电梯标识不一致，禁止回填");
            }
            requirePlatformValue(platform, "elevatorCode");
            requirePlatformValue(platform, "registrationCode");
            requirePlatformValue(platform, "deviceCode");
            requireCompatible(order.elevatorCode, platform.get("elevatorCode"), "电梯编码");
            requireCompatible(order.regCode, platform.get("registrationCode"), "注册代码");
            requireCompatible(order.deviceCode, platform.get("deviceCode"), "设备代码");
            order.elevatorCode = text(platform.get("elevatorCode"));
            order.regCode = text(platform.get("registrationCode"));
            order.deviceCode = text(platform.get("deviceCode"));
            elevator.elevatorCode = order.elevatorCode;
            elevator.regCode = order.regCode;
            elevator.deviceCode = order.deviceCode;
            if (hasRealValue(platform.get("useUnitEntityId"))) {
                elevator.useUnitEntityId = text(platform.get("useUnitEntityId"));
            }
            if (hasRealValue(platform.get("elevatorAdminister"))) {
                elevator.elevatorAdminister = text(platform.get("elevatorAdminister"));
            }
            if (hasRealValue(platform.get("elevatorAdministerPhone"))) {
                elevator.elevatorAdministerPhone = text(platform.get("elevatorAdministerPhone"));
            }
            if (hasRealValue(platform.get("emergencyPhone"))) {
                elevator.emergencyPhone = text(platform.get("emergencyPhone"));
            }
        }
        Long duplicates = elevatorMapper.selectCount(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, order.elevatorCode));
        if (duplicates != null && duplicates > 0) {
            throw new BizException(422, "电梯编码已存在于电梯台账，禁止重复建档");
        }
        Long duplicateRegCodes = elevatorMapper.selectCount(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getRegCode, order.regCode));
        if (duplicateRegCodes != null && duplicateRegCodes > 0) {
            throw new BizException(422, "注册代码已存在于电梯台账，禁止重复建档");
        }
        Long duplicateDeviceCodes = elevatorMapper.selectCount(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getDeviceCode, order.deviceCode));
        if (duplicateDeviceCodes != null && duplicateDeviceCodes > 0) {
            throw new BizException(422, "设备代码已存在于电梯台账，禁止重复建档");
        }
    }

    private static boolean matches(Map<String, String> conditions, Map<String, Object> candidate) {
        return sameValue(conditions.get("factoryNumber"), candidate.get("factoryNumber"))
                && (conditions.get("registrationCode").isEmpty()
                    || sameValue(conditions.get("registrationCode"), candidate.get("registrationCode")))
                && (conditions.get("deviceCode").isEmpty()
                    || sameValue(conditions.get("deviceCode"), candidate.get("deviceCode")));
    }

    private static boolean sameValue(String local, Object platform) {
        return !local.isEmpty() && local.equals(text(platform));
    }

    private static void requirePlatformValue(Map<String, Object> platform, String field) {
        if (text(platform.get(field)).isEmpty()) {
            throw new BizException(422, "2.7 返回缺少 " + field + "，请先在监管平台补齐注册信息");
        }
    }

    private static void requireCompatible(String local, Object platform, String label) {
        if (!text(local).isEmpty() && !text(local).equals(text(platform))) {
            throw new BizException(422, label + "与 2.7 返回不一致，请人工核对后归档");
        }
    }

    private static boolean hasRealValue(Object value) {
        String s = text(value);
        return !s.isEmpty() && !s.contains("*") && !"null".equalsIgnoreCase(s) && !"undefined".equalsIgnoreCase(s);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
