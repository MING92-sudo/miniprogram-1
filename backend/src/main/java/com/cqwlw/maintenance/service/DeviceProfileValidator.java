package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备信息档案字段口径（用户 2026-11-19 需求，docs/01 §3.4.1）：
 *
 * <p>档案共 8 项，其中**前 5 项为上传维保记录（platform 2.6）的必传项**，缺任一项都不得生成/上传记录：
 * 电梯编码（elevatorCode）、设备代码（deviceCode）、注册代码（registrationCode）、
 * 单位内编号（insideNumber）、出厂编号（factoryNumber）。
 *
 * <p>后 3 项选填：项目名称/小区名称（projectName）、所在区域（regionId）、年检日期（nextCheckDate）。
 *
 * <p>**标签为对外口径，不得改写**：注册代码 不是「产品编号」也不是「设备码」，
 * 电梯编码（本系统内部编码/二维码内容）与设备代码（监管平台设备代码）是两个不同字段。
 */
public final class DeviceProfileValidator {

    private DeviceProfileValidator() {
    }

    /** 单字段定义：key = 接口字段名，label = 界面标签（对外口径，测试锁定） */
    public record Field(String key, String label) {
    }

    /** 8 项档案字段，顺序即界面顺序（前 5 必填） */
    public static final List<Field> FIELDS = List.of(
            new Field("elevatorCode", "电梯编码"),
            new Field("deviceCode", "设备代码"),
            new Field("registrationCode", "注册代码"),
            new Field("insideNumber", "单位内编号"),
            new Field("factoryNumber", "出厂编号"),
            new Field("projectName", "项目名称（小区名称）"),
            new Field("regionId", "所在区域"),
            new Field("nextCheckDate", "年检日期"));

    /** 必传项（上传维保记录的 5 项），顺序固定 */
    public static final List<Field> REQUIRED = FIELDS.subList(0, 5);

    /** 档案取值（key 用接口字段名，注册代码取 DB reg_code） */
    public static Map<String, String> valuesOf(Elevator el) {
        Map<String, String> m = new LinkedHashMap<>();
        if (el == null) {
            FIELDS.forEach(f -> m.put(f.key(), ""));
            return m;
        }
        m.put("elevatorCode", nz(el.elevatorCode));
        m.put("deviceCode", nz(el.deviceCode));
        m.put("registrationCode", nz(el.regCode));
        m.put("insideNumber", nz(el.insideNumber));
        m.put("factoryNumber", nz(el.factoryNumber));
        m.put("projectName", nz(el.projectName));
        m.put("regionId", nz(el.regionId));
        m.put("nextCheckDate", el.nextCheckDate == null ? "" : el.nextCheckDate.toString());
        return m;
    }

    /** 缺失的必传项标签（空列表 = 档案齐备） */
    public static List<String> missingRequired(Elevator el) {
        Map<String, String> v = valuesOf(el);
        List<String> missing = new ArrayList<>();
        for (Field f : REQUIRED) {
            if (isBlank(v.get(f.key()))) {
                missing.add(f.label());
            }
        }
        return missing;
    }

    /**
     * 上传维保记录前置校验：5 项必传字段缺一不可。
     * 抛出 422 并**逐项列出缺失字段的中文标签**，让现场一眼知道缺什么、去哪里补。
     */
    public static void requireForRecord(Elevator el) {
        List<String> missing = missingRequired(el);
        if (!missing.isEmpty()) {
            throw new BizException(422, "设备信息档案缺少上传维保记录的必传项：" + String.join("、", missing)
                    + "；请先在 电梯台账 › 设备信息档案 补全后再上传维保记录");
        }
    }

    /**
     * 新建设备信息档案：5 项必填，一次列全（不做逐字段打断）。
     */
    public static void requireForCreate(Elevator el) {
        List<String> missing = missingRequired(el);
        if (!missing.isEmpty()) {
            throw new BizException(422, "设备信息档案必填项未填写：" + String.join("、", missing));
        }
    }

    /** 必传项字段 key 集合（更新时禁止清空） */
    public static boolean isRequiredKey(String key) {
        return REQUIRED.stream().anyMatch(f -> f.key().equals(key));
    }

    /** 必传项的界面标签，未命中返回 key 本身 */
    public static String labelOf(String key) {
        return FIELDS.stream().filter(f -> f.key().equals(key)).map(Field::label).findFirst().orElse(key);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
