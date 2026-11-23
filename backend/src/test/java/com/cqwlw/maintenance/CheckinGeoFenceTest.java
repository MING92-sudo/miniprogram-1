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
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.ChecklistService;
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

/** ④签到缺定位 → 422（合规底线：定位失败如实提示，严禁伪造坐标兜底）。 */
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
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
        orderMapper = mock(WorkOrderMapper.class);
        when(orderMapper.update(any(), any())).thenReturn(1);
        elevatorMapper = mock(ElevatorMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class),
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
    void selfCollectedBaselineFencesImmediately() {
        elevator.geoStatus = "SELF_COLLECTED";
        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(EL_LAT + 0.01, EL_LNG), EMP));
        assertEquals(1001, e.getCode(), "自采坐标自回填起即为围栏基准（D7/D9），超距应 1001");
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
        when(elevatorMapper.backfillGeoIfMissing(any(), any(), any())).thenReturn(1);
        Map<String, Object> r = service.checkin(ORDER, body(EL_LAT + 0.5, EL_LNG), EMP);
        assertEquals("UNKNOWN", r.get("geoStatus"), "坐标未登记只留痕不拦截（码表 1005 语义）");
        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals(Boolean.TRUE, r.get("geoBackfilled"), "无坐标电梯应以本次签到定位回填");
        assertEquals("SELF_COLLECTED", elevator.geoStatus);
        verify(elevatorMapper).backfillGeoIfMissing(any(), any(), any());
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
    }

    @Test
    void backfillLosingRaceDoesNotOverwriteManualGeo() {
        elevator.lat = null;
        elevator.lng = null;
        when(elevatorMapper.backfillGeoIfMissing(any(), any(), any())).thenReturn(0);

        Map<String, Object> r = service.checkin(ORDER, body(EL_LAT + 0.5, EL_LNG), EMP);

        assertEquals(false, r.get("geoBackfilled"));
        assertEquals(null, elevator.geoStatus);
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
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

    @Test
    void nonFiniteAndOutOfRangeCoordinatesRejectedBeforeAnyWrite() {
        String[][] invalid = {
                {"NaN", "106.5"}, {"Infinity", "106.5"}, {"-Infinity", "106.5"},
                {"29.6", "NaN"}, {"29.6", "Infinity"}, {"29.6", "-Infinity"},
                {"91", "106.5"}, {"-91", "106.5"}, {"29.6", "181"}, {"29.6", "-181"}
        };
        for (String[] coordinate : invalid) {
            Map<String, Object> b = body(EL_LAT, EL_LNG);
            b.put("latitude", coordinate[0]);
            b.put("longitude", coordinate[1]);
            BizException e = assertThrows(BizException.class, () -> service.checkin(ORDER, b, EMP),
                    () -> "非法坐标必须拒绝：" + coordinate[0] + "," + coordinate[1]);
            assertEquals(422, e.getCode());
        }
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
        assertEquals("PENDING", order.status);
    }

    @Test
    void clientCollectedAtCannotForgeCheckinTime() {
        Map<String, Object> b = body(EL_LAT, EL_LNG);
        b.put("collectedAt", "2000-01-01 00:00:00");
        var before = com.cqwlw.maintenance.util.TimeUtil.now().minusSeconds(1);
        service.checkin(ORDER, b, EMP);
        var after = com.cqwlw.maintenance.util.TimeUtil.now().plusSeconds(1);
        assertNotNull(order.checkinTime);
        assertTrue(!order.checkinTime.isBefore(before) && !order.checkinTime.isAfter(after),
                "作业起始时间必须取服务器时间，客户端时间只能作为额外留痕");
    }
}
