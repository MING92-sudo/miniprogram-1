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
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签到定位校验（docs/02 §5.4 阈值配置 + docs/04 A.0.1 码表 1001/1005）：
 * ①电梯坐标已登记 → Haversine 超阈拦截 1001，且不产生任何副作用（不落库、不改状态）；
 * ②电梯级阈值优先于全局默认；③坐标未登记 → geoStatus=UNKNOWN，只留痕不拦截；
 * ④签到缺定位 → 422（合规底线：定位失败如实提示，严禁伪造坐标兜底）。
 */
class CheckinGeoFenceTest {

    private static final String ORDER = "wo_geo";
    private static final String EMP = "emp_li";
    private static final String PID = "pid_li";
    private static final String ELEVATOR = "el_geo";
    /** 电梯档案登记坐标（重庆渝北）；纬度 0.001° ≈ 111 米 */
    private static final double EL_LAT = 29.600000d;
    private static final double EL_LNG = 106.500000d;

    private WorkOrderMapper orderMapper;
    private ElevatorMapper elevatorMapper;
    private WorkOrderService service;
    private Elevator elevator;
    private WorkOrder order;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(ChecklistService.class),
                mock(DispatchService.class), mock(PlatformReportService.class),
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props), mock(FileStorageService.class), TxTestSupport.noopTx(), props);

        order = new WorkOrder();
        order.id = ORDER;
        order.elevatorId = ELEVATOR;
        order.status = "PENDING";
        order.workerPlatformId = PID;
        when(orderMapper.selectById(ORDER)).thenReturn(order);

        elevator = new Elevator();
        elevator.id = ELEVATOR;
        elevator.lat = BigDecimal.valueOf(EL_LAT);
        elevator.lng = BigDecimal.valueOf(EL_LNG);
        when(elevatorMapper.selectById(ELEVATOR)).thenReturn(elevator);

        Employee emp = new Employee();
        emp.id = EMP;
        emp.name = "李强";
        emp.role = "WORKER";
        emp.platformId = PID;
        when(employeeMapper.selectById(EMP)).thenReturn(emp);
        when(employeeMapper.selectList(any())).thenReturn(List.of(emp));
    }

    private static Map<String, Object> body(double lat, double lng) {
        Map<String, Object> b = new HashMap<>();
        b.put("role", "PRINCIPAL");
        b.put("latitude", String.valueOf(lat));
        b.put("longitude", String.valueOf(lng));
        b.put("photoFileId", "f_1");
        return b;
    }

    @Test
    void nearbyCheckinPasses() {
        Map<String, Object> r = service.checkin(ORDER, body(EL_LAT + 0.0005, EL_LNG), EMP);
        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals("PROVIDED", r.get("geoStatus"));
        assertTrue(((Number) r.get("distance")).doubleValue() <= 200d, "约 55 米应在默认 200 米阈值内");
        assertEquals("PROCESSING", order.status);
    }

    @Test
    void farCheckinRejectedWith1001AndNoSideEffect() {
        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(EL_LAT + 0.01, EL_LNG), EMP));
        assertEquals(1001, e.getCode(), "超阈应返回 1001（docs/04 A.0.1）");
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        assertEquals("PENDING", order.status, "拦截后不得改状态（无副作用）");
    }

    @Test
    void elevatorThresholdOverridesGlobalDefault() {
        elevator.checkinThreshold = 30;
        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(EL_LAT + 0.0005, EL_LNG), EMP));
        assertEquals(1001, e.getCode(), "电梯级阈值 30 米优先于全局 200 米");
    }

    @Test
    void unknownElevatorGeoNotIntercepted() {
        elevator.lat = null;
        elevator.lng = null;
        Map<String, Object> r = service.checkin(ORDER, body(EL_LAT + 0.5, EL_LNG), EMP);
        assertEquals("UNKNOWN", r.get("geoStatus"), "坐标未登记只留痕不拦截（码表 1005 语义）");
        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals(Boolean.TRUE, r.get("geoBackfilled"), "无坐标电梯应以本次签到定位回填");
        // 无坐标电梯签到后回填坐标并标记为现场采集（用户需求③ + docs/02 §5.4 geo_status）
        assertEquals("SELF_COLLECTED", elevator.geoStatus);
        verify(elevatorMapper).updateById(elevator);
    }

    @Test
    void missingCoordinatesRejected422() {
        Map<String, Object> b = new HashMap<>();
        b.put("role", "PRINCIPAL");
        BizException e = assertThrows(BizException.class, () -> service.checkin(ORDER, b, EMP));
        assertEquals(422, e.getCode(), "签到必须携带定位坐标");
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void checkinExtraKeepsDistanceAndGeoStatus() {
        service.checkin(ORDER, body(EL_LAT + 0.0005, EL_LNG), EMP);
        assertNotNull(order.checkinExtraJson);
        assertTrue(order.checkinExtraJson.contains("geoStatus"), "签到留痕应含定位校验结果");
        assertTrue(order.checkinExtraJson.contains("distance"), "签到留痕应含实测距离");
    }
}
