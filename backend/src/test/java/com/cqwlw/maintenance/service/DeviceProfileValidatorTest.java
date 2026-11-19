package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备信息档案字段口径（用户 2026-11-19）：8 项字段、前 5 项为上传维保记录必传项。
 * 标签属对外口径——`注册代码` 不等于 `产品编号`，`电梯编码` 不等于 `设备代码`，标签写错即测试失败。
 */
class DeviceProfileValidatorTest {

    private static Elevator complete() {
        Elevator el = new Elevator();
        el.elevatorCode = "EM-2024-001";
        el.deviceCode = "DT-CQ-2024-001";
        el.regCode = "TSCQ5001120001";
        el.insideNumber = "KT-01";
        el.factoryNumber = "SGL20131212-1";
        el.projectName = "世纪大厦";
        el.regionId = "rg_1";
        el.nextCheckDate = LocalDate.of(2027, 3, 1);
        return el;
    }

    @Test
    void eightFieldsInFixedOrder() {
        assertEquals(List.of("电梯编码", "设备代码", "注册代码", "单位内编号", "出厂编号",
                        "项目名称（小区名称）", "所在区域", "年检日期"),
                DeviceProfileValidator.FIELDS.stream().map(DeviceProfileValidator.Field::label).toList());
        assertEquals(List.of("elevatorCode", "deviceCode", "registrationCode", "insideNumber", "factoryNumber",
                        "projectName", "regionId", "nextCheckDate"),
                DeviceProfileValidator.FIELDS.stream().map(DeviceProfileValidator.Field::key).toList());
    }

    @Test
    void firstFiveAreTheRequiredUploadItems() {
        assertEquals(5, DeviceProfileValidator.REQUIRED.size());
        assertEquals(List.of("电梯编码", "设备代码", "注册代码", "单位内编号", "出厂编号"),
                DeviceProfileValidator.REQUIRED.stream().map(DeviceProfileValidator.Field::label).toList());
    }

    @Test
    void completeArchiveHasNoMissingField() {
        assertTrue(DeviceProfileValidator.missingRequired(complete()).isEmpty());
        assertDoesNotThrow(() -> DeviceProfileValidator.requireForRecord(complete()));
    }

    @Test
    void optionalFieldsDoNotBlockRecordUpload() {
        Elevator el = complete();
        el.projectName = null;
        el.regionId = null;
        el.nextCheckDate = null;
        assertDoesNotThrow(() -> DeviceProfileValidator.requireForRecord(el));
    }

    /** 缺失项按固定顺序逐项列名，提示必须能指导现场补哪一项 */
    @Test
    void missingLabelsAreListedInFieldOrder() {
        Elevator el = complete();
        el.deviceCode = "  ";
        el.factoryNumber = null;
        assertEquals(List.of("设备代码", "出厂编号"), DeviceProfileValidator.missingRequired(el));

        BizException e = assertThrows(BizException.class, () -> DeviceProfileValidator.requireForRecord(el));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("设备代码、出厂编号"), e.getMessage());
        assertTrue(e.getMessage().contains("设备信息档案"), e.getMessage());
        assertTrue(e.getMessage().contains("上传维保记录"), e.getMessage());
    }

    @Test
    void nullElevatorReportsAllFiveRequired() {
        assertEquals(List.of("电梯编码", "设备代码", "注册代码", "单位内编号", "出厂编号"),
                DeviceProfileValidator.missingRequired(null));
    }

    /** 注册代码读的是 DB 列 reg_code（对外 key=registrationCode），不能误取产品编号 product_no */
    @Test
    void registrationCodeMapsToRegCodeColumn() {
        Elevator el = complete();
        el.productNo = "PRODUCT-9";
        el.regCode = "REG-9";
        assertEquals("REG-9", DeviceProfileValidator.valuesOf(el).get("registrationCode"));
        assertEquals("电梯编码", DeviceProfileValidator.labelOf("elevatorCode"));
        assertEquals("注册代码", DeviceProfileValidator.labelOf("registrationCode"));
        assertTrue(DeviceProfileValidator.isRequiredKey("factoryNumber"));
        assertEquals(false, DeviceProfileValidator.isRequiredKey("projectName"));
    }
}
