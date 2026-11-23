package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ③配合人签到先做身份比对（login.platformId == order.assistantPlatformId，不符 1003 且动态码不被消费），再消费动态码； */
class WorkOrderPartnerTest {

    private static final String ORDER = "wo_partner";
    private static final String ELEVATOR = "el_partner";

    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private EmployeeMapper employeeMapper;
    private TxTestSupport.CapturingTx tx;
    private WorkOrderService service;
    private WorkOrder order;
    private Elevator elevator;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        tx = TxTestSupport.capturingTx();
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class), mock(PlatformReportService.class),
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                mock(FileStorageService.class), tx.template, props);

        // 已认领工单：张三主维保、区域快照 rg_yubei、未指派配合人
        order = new WorkOrder();
        order.id = ORDER;
        order.elevatorId = ELEVATOR;
        order.status = "PROCESSING";
        order.regionId = "rg_yubei";
        order.workerName = "张三";
        order.workerPlatformId = "pid_zhang";
        when(orderMapper.selectById(ORDER)).thenReturn(order);
        // 动态码原子消费默认成功（1 行命中）；具体用例可覆写
        when(orderMapper.consumeDynamicCodeIfUnused(any(), any(), any())).thenReturn(1);

        // 无坐标电梯：默认不触发围栏，测试聚焦配合人链路；距离用例自行补坐标
        elevator = new Elevator();
        elevator.id = ELEVATOR;
        when(elevatorMapper.selectById(ELEVATOR)).thenReturn(elevator);

        stub(emp("emp_zhang", "张三", "pid_zhang", "rg_yubei", "13800000001"));
        stub(emp("emp_wang", "王五", "pid_wang", "rg_yubei", "13800000002"));
        stub(emp("emp_zhao", "赵六", "pid_zhao", "rg_jiangbei", "13800000003"));
        // privileged 管理员（越过归属谓词）用于"非指定人冒签"场景——身份比对必须拦在动态码消费之前
        Employee admin = emp("emp_admin", "管理员", "pid_admin", "rg_yubei", "13800000009");
        admin.role = "ADMIN";
        stub(admin);
    }

    private static Employee emp(String id, String name, String platformId, String regionId, String phone) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = "WORKER";
        e.platformId = platformId;
        e.regionId = regionId;
        e.phone = phone;
        return e;
    }

    private void stub(Employee e) {
        when(employeeMapper.selectById(e.id)).thenReturn(e);
    }

    private static Map<String, Object> assistantBody(String code, double lat, double lng) {
        Map<String, Object> b = new HashMap<>();
        b.put("role", "ASSISTANT");
        b.put("latitude", String.valueOf(lat));
        b.put("longitude", String.valueOf(lng));
        if (code != null) {
            b.put("dynamicCode", code);
        }
        return b;
    }

    @Test
    void partnerCandidatesSameRegionOnly() {
        Employee noPid = emp("emp_qian", "钱七", null, "rg_yubei", "13800000004");
        Employee disabled = emp("emp_sun", "孙八", "pid_sun", "rg_yubei", "13800000005");
        disabled.enabled = false;
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                emp("emp_zhang", "张三", "pid_zhang", "rg_yubei", "13800000001"),
                emp("emp_wang", "王五", "pid_wang", "rg_yubei", "13800000002"),
                emp("emp_zhao", "赵六", "pid_zhao", "rg_jiangbei", "13800000003"),
                noPid, disabled));

        Map<String, Object> r = service.partnerCandidates(ORDER, "emp_zhang");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(1, list.size(), "候选只含同区域+持平台ID+在职+非主维保的王五");
        Map<String, Object> c = list.get(0);
        assertEquals("emp_wang", c.get("id"));
        assertEquals("王五", c.get("name"));
        assertEquals("pid_wang", c.get("platformId"));
        assertEquals("13800000002", c.get("phone"));
    }

    @Test
    void assignPartnerRejectsOutOfRegion() {
        BizException e = assertThrows(BizException.class,
                () -> service.assignPartner(ORDER, Map.of("employeeId", "emp_zhao"), "emp_zhang"));
        assertEquals(422, e.getCode(), "跨区域候选人服务端复核必须 422（不信任前端列表）");
        assertNull(order.assistantPlatformId, "复核拦截不得落库");
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void assignPartnerRejectsAfterAssistantCheckin() {
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_wang";
        order.assistantCheckinExtraJson = "{\"role\":\"ASSISTANT\"}";

        BizException e = assertThrows(BizException.class,
                () -> service.assignPartner(ORDER, Map.of("employeeId", "emp_wang"), "emp_zhang"));
        assertEquals(1003, e.getCode(), "配合人已签到后不可改选");
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void assignPartnerStoresAssistant() {
        Map<String, Object> r = service.assignPartner(ORDER, Map.of("employeeId", "emp_wang"), "emp_zhang");

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("王五", order.assistantName);
        assertEquals("pid_wang", order.assistantPlatformId);
        verify(orderMapper).updateById(order);
    }

    @Test
    void reassignPartnerClearsPreviousSignature() {
        order.assistantName = "旧配合人";
        order.assistantPlatformId = "pid_old";
        order.assistantSignatureFileId = "file_old_signature";
        order.assistantSignatureAt = java.time.LocalDateTime.now();

        service.assignPartner(ORDER, Map.of("employeeId", "emp_wang"), "emp_zhang");

        assertEquals("王五", order.assistantName);
        assertNull(order.assistantSignatureFileId);
        assertNull(order.assistantSignatureAt);
        assertTrue(tx.committed, "改选配合人与清旧签名必须处在同一事务");
        verify(orderMapper).updateById(order);
        verify(orderMapper).clearAssistantSignature(ORDER);
    }

    @Test
    void assistantCheckinRejectsWrongPersonBeforeConsumingCode() {
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_wang";
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, assistantBody(code, 29.61, 106.51), "emp_admin"));
        assertEquals(1003, e.getCode(), "非指定配合人必须 1003");
        assertTrue(e.getMessage().contains("未被指定为配合人"), "提示语：" + e.getMessage());
        verify(orderMapper, never()).consumeDynamicCodeIfUnused(any(), any(), any());
        assertNull(order.assistantCheckinExtraJson, "身份不符不得写签到留痕");

        Map<String, Object> r = service.checkin(ORDER, assistantBody(code, 29.61, 106.51), "emp_wang");
        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals("ASSISTANT", r.get("role"));
        assertNotNull(order.assistantCheckinExtraJson);
    }

    @Test
    void assistantCheckinNeverBlockedByDistance() {
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_wang";
        // 电梯坐标已登记（重庆），配合人在北京签到 ≈ 145 万米，远超默认 200 米阈值
        elevator.lat = BigDecimal.valueOf(29.6);
        elevator.lng = BigDecimal.valueOf(106.5);
        elevator.geoStatus = "PROVIDED";
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        Map<String, Object> r = service.checkin(ORDER, assistantBody(code, 39.9042, 116.4074), "emp_wang");

        assertEquals(Boolean.TRUE, r.get("passed"), "D6：配合人永不因距离被 1001 拦截");
        assertEquals("ASSISTANT_EXEMPT", r.get("geoStatus"));
        assertNotNull(r.get("distance"), "距离照常留痕（M2）");
        assertTrue(((Number) r.get("distance")).doubleValue() > 1000000d,
                "留痕距离应为实际算出的超远距离：" + r.get("distance"));
        assertTrue(order.assistantCheckinExtraJson.contains("ASSISTANT_EXEMPT"));
    }

    @Test
    void assistantCheckinStillRequiresCoordinates() {
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_wang";
        Map<String, Object> b = new HashMap<>();
        b.put("role", "ASSISTANT");
        b.put("dynamicCode", "123456");

        BizException e = assertThrows(BizException.class, () -> service.checkin(ORDER, b, "emp_wang"));
        assertEquals(422, e.getCode(), "M2：配合人免围栏但定位仍必填，缺失 422");
        verify(orderMapper, never()).consumeDynamicCodeIfUnused(any(), any(), any());
        assertNull(order.assistantCheckinExtraJson);
    }

    @Test
    void assistantCheckinDoesNotBackfillElevatorCoordinates() {
        order.assistantName = "王五";
        order.assistantPlatformId = "pid_wang";
        // 电梯无坐标（setUp 默认）；配合人在北京远距签到
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        Map<String, Object> r = service.checkin(ORDER, assistantBody(code, 39.9042, 116.4074), "emp_wang");

        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals(Boolean.FALSE, r.get("geoBackfilled"), "D9：配合人签到不得回填电梯坐标");
        assertNull(elevator.lat, "电梯坐标应保持为空，围栏基线仅由主维保现场回填");
        assertNull(elevator.lng, "电梯坐标应保持为空，围栏基线仅由主维保现场回填");
        assertNull(elevator.geoStatus);
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
    }

    @Test
    void legacyOrderWithoutAssistantPlatformIdKeepsCodeOnlyFlow() {
        order.regionId = null;
        order.assistantName = "王五";
        order.assistantPlatformId = null;
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        Map<String, Object> r = service.checkin(ORDER, assistantBody(code, 29.61, 106.51), "emp_admin");

        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals("ASSISTANT", r.get("role"));
        assertNotNull(order.assistantCheckinExtraJson);
    }

    @Test
    void pooledOrderWithoutAssignedAssistantRejectsAssistantCheckin() {
        order.assistantName = "";
        order.assistantPlatformId = null;
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, assistantBody(code, 29.61, 106.51), "emp_admin"));
        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("尚未指定配合人"), "提示语：" + e.getMessage());
        verify(orderMapper, never()).consumeDynamicCodeIfUnused(any(), any(), any());
        assertNull(order.assistantCheckinExtraJson, "守卫拦截不得写签到留痕");
        assertNull(order.dynamicCode, "守卫拦截不得吞动态码");
    }

    /** I2 第二信号：不限区域池单（regionId 空）以主维保签到打标 claimedViaPool 判定，同样 fail-closed */
    @Test
    void claimedViaPoolOrderWithoutAssignedAssistantRejectsAssistantCheckin() {
        order.regionId = null;
        order.checkinExtraJson = "{\"role\":\"PRINCIPAL\",\"claimedViaPool\":true}";
        order.assistantName = "";
        order.assistantPlatformId = null;
        String code = String.valueOf(service.issueDynamicCode(ORDER, "emp_zhang").get("code"));

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, assistantBody(code, 29.61, 106.51), "emp_admin"));
        assertEquals(1003, e.getCode());
        assertTrue(e.getMessage().contains("尚未指定配合人"), "提示语：" + e.getMessage());
        verify(orderMapper, never()).consumeDynamicCodeIfUnused(any(), any(), any());
        assertNull(order.assistantCheckinExtraJson);
    }
}
