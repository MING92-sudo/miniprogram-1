package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双人动态码时效化 + 主维保/配合人员分别签到（docs/01 §3.7.2、docs/03 §3.2 项6「60 秒动态码，每 5 秒刷新」、docs/05 §2.5）。
 * 原实现为固定 888888（无时效、不绑工单、可无限重放），且工单只允许一人签到（第二人 1003），双人作业闭环断裂。
 * 放在 service 包内以便用包可见的 currentStep()/dynamicCode() 精确校验 60 秒窗口边界。
 */
class DynamicCodeTest {

    private static final String ORDER = "wo_dual";
    private static final String OTHER_ORDER = "wo_other";
    private static final String EMP_P = "emp_zhang";
    private static final String PID_P = "pid_zhang";
    private static final String EMP_A = "emp_li";
    private static final String PID_A = "pid_li";

    private WorkOrderMapper orderMapper;
    private WorkOrderService service;
    private WorkOrder order;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(orderMapper, mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(ChecklistService.class),
                mock(DispatchService.class), mock(PlatformReportService.class),
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props), props);

        order = order(ORDER);
        when(orderMapper.selectById(ORDER)).thenReturn(order);
        when(orderMapper.selectById(OTHER_ORDER)).thenReturn(order(OTHER_ORDER));
        when(employeeMapper.selectById(EMP_P)).thenReturn(employee(EMP_P, "张伟", PID_P));
        when(employeeMapper.selectById(EMP_A)).thenReturn(employee(EMP_A, "李强", PID_A));
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                employee(EMP_P, "张伟", PID_P), employee(EMP_A, "李强", PID_A)));
    }

    private static WorkOrder order(String id) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.elevatorId = "el_1";
        o.status = "PENDING";
        o.workerPlatformId = PID_P;
        o.assistantPlatformId = PID_A;
        return o;
    }

    private static Employee employee(String id, String name, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = "WORKER";
        e.platformId = platformId;
        e.groupName = "维保一班";
        return e;
    }

    private static Map<String, Object> checkin(String role, String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("role", role);
        b.put("latitude", "29.6000");
        b.put("longitude", "106.5000");
        b.put("photoFileId", "f_" + role);
        if (code != null) {
            b.put("dynamicCode", code);
        }
        return b;
    }

    /** 当前 60 秒窗口内全部可接受的码（12 个 5 秒步长） */
    private Set<String> validCodes(String orderId) {
        Set<String> s = new HashSet<>();
        long step = service.currentStep();
        for (long i = step; i > step - 12; i--) {
            s.add(service.dynamicCode(orderId, i));
        }
        return s;
    }

    private static String invalidCode(Set<String> valid) {
        for (int i = 0; i < 1000000; i++) {
            String c = String.format("%06d", i);
            if (!valid.contains(c)) {
                return c;
            }
        }
        throw new IllegalStateException("无可用反例码");
    }

    @Test
    void issuedCodeIsSixDigitsWithStepAndTtl() {
        Map<String, Object> r = service.issueDynamicCode(ORDER, EMP_P);
        assertTrue(String.valueOf(r.get("code")).matches("\\d{6}"), "动态码应为 6 位数字");
        assertEquals(5, r.get("stepSeconds"), "每 5 秒刷新（docs/03 §3.2 项6）");
        assertEquals(60, r.get("ttlSeconds"), "有效期 60 秒");
        assertNotNull(r.get("expiresAt"));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void currentAndPreviousWindowCodesVerify() {
        String code = String.valueOf(service.issueDynamicCode(ORDER, EMP_P).get("code"));
        assertEquals(Boolean.TRUE, service.verifyDynamicCode(ORDER, Map.of("code", code), EMP_A).get("ok"));
        // 上一个 5 秒步长的码在 60 秒有效期内同样可校验（避免配合人员输入途中换码即失败）
        String prev = service.dynamicCode(ORDER, service.currentStep() - 1);
        assertEquals(Boolean.TRUE, service.verifyDynamicCode(ORDER, Map.of("code", prev), EMP_A).get("ok"));
    }

    @Test
    void outOfWindowCodeRejectedAsExpired() {
        String stale = service.dynamicCode(ORDER, service.currentStep() - 13);
        BizException e = assertThrows(BizException.class,
                () -> service.verifyDynamicCode(ORDER, Map.of("code", stale), EMP_A));
        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("过期"), "超出 60 秒窗口应提示过期");
    }

    @Test
    void wrongCodeRejected1003() {
        String bad = invalidCode(validCodes(ORDER));
        BizException e = assertThrows(BizException.class,
                () -> service.verifyDynamicCode(ORDER, Map.of("code", bad), EMP_A));
        assertEquals(1003, e.getCode());
    }

    /** 码与工单绑定：A 单的码不能用于 B 单（原固定 888888 无此约束） */
    @Test
    void codeIsBoundToOrder() {
        String code = String.valueOf(service.issueDynamicCode(ORDER, EMP_P).get("code"));
        BizException e = assertThrows(BizException.class,
                () -> service.verifyDynamicCode(OTHER_ORDER, Map.of("code", code), EMP_A));
        assertEquals(1003, e.getCode());
    }

    @Test
    void assistantCheckinConsumesCodeAndCannotReplay() {
        String code = String.valueOf(service.issueDynamicCode(ORDER, EMP_P).get("code"));
        Map<String, Object> r = service.checkin(ORDER, checkin("ASSISTANT", code), EMP_A);
        assertEquals("ASSISTANT", r.get("role"));
        assertEquals(code, order.dynamicCode, "已消费的码应记录以防重放");
        assertNotNull(order.assistantCheckinExtraJson);

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, checkin("ASSISTANT", code), EMP_A));
        assertEquals(1003, e.getCode(), "重复签到/重放动态码必须拒绝");
    }

    @Test
    void assistantCheckinRequiresCode() {
        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, checkin("ASSISTANT", null), EMP_A));
        assertEquals(422, e.getCode());
    }

    /** docs/01 §3.7.2：主维保与配合人员分别签到，两人留痕互不覆盖 */
    @Test
    void principalAndAssistantCheckinSeparately() {
        service.checkin(ORDER, checkin("PRINCIPAL", null), EMP_P);
        String principalExtra = order.checkinExtraJson;
        assertNotNull(principalExtra);
        assertEquals("PROCESSING", order.status);

        String code = String.valueOf(service.issueDynamicCode(ORDER, EMP_P).get("code"));
        service.checkin(ORDER, checkin("ASSISTANT", code), EMP_A);

        assertEquals(principalExtra, order.checkinExtraJson, "配合人员签到不得覆盖主维保留痕");
        assertNotNull(order.assistantCheckinExtraJson);
        assertTrue(order.assistantCheckinExtraJson.contains("f_ASSISTANT"));
        assertNotEquals(order.checkinExtraJson, order.assistantCheckinExtraJson);
    }
}
