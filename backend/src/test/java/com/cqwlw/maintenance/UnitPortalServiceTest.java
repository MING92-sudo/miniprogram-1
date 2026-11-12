package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.DirectoryService;
import com.cqwlw.maintenance.service.UnitPortalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用单位门户（W3，Task 10，docs/14 §4）：只见本 useUnitId 的电梯/记录/急修；
 * 报修复用急修链路，并派给该梯责任人（无责任人则留待派单）。
 */
class UnitPortalServiceTest {

    private final ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
    private final MaintainRecordMapper recordMapper = mock(MaintainRecordMapper.class);
    private final FaultMapper faultMapper = mock(FaultMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final DirectoryService directoryService = mock(DirectoryService.class);

    private UnitPortalService service() {
        return new UnitPortalService(elevatorMapper, recordMapper, faultMapper, employeeMapper, directoryService);
    }

    private static Elevator elevator(String id, String code, String useUnitId, String workerPlatformId) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = code;
        el.elevatorName = code + " 梯";
        el.useUnitId = useUnitId;
        el.workerPlatformId = workerPlatformId;
        return el;
    }

    private static MaintainRecord record(String id, String code) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = code;
        r.elevatorName = code + " 梯";
        r.workType = "半月维保";
        r.confirmStatus = "PENDING";
        return r;
    }

    private static Fault fault(String id, String code) {
        Fault f = new Fault();
        f.id = id;
        f.elevatorCode = code;
        f.status = "OPEN";
        f.descr = "门关不上";
        return f;
    }

    /** 其他单位的电梯/记录/急修必须被过滤掉（含越权读） */
    @Test
    void scopesToOwnUnit() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(
                elevator("el_1", "EM-1", "uu_1", null),
                elevator("el_2", "EM-2", "uu_2", null)));
        when(recordMapper.selectList(any())).thenReturn(List.of(
                record("ur_1", "EM-1"), record("ur_2", "EM-2")));
        when(faultMapper.selectList(any())).thenReturn(List.of(
                fault("ft_1", "EM-1"), fault("ft_2", "EM-2")));

        UnitPortalService svc = service();
        assertEquals(1, svc.elevators("uu_1").size());
        assertEquals("EM-1", svc.elevators("uu_1").get(0).get("elevatorCode"));
        assertEquals(1, svc.records("uu_1").size());
        assertEquals("EM-1", svc.records("uu_1").get(0).get("elevatorCode"));
        assertEquals(1, svc.faults("uu_1").size());
        assertEquals("EM-1", svc.faults("uu_1").get(0).get("elevatorCode"));
    }

    /** 待签字 = 本单位电梯中未确认的维保记录 */
    @Test
    void pendingSignsOnlyUnconfirmedOwnRecords() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        MaintainRecord confirmed = record("ur_done", "EM-1");
        confirmed.confirmStatus = "CONFIRMED";
        when(recordMapper.selectList(any())).thenReturn(List.of(record("ur_wait", "EM-1"), confirmed));

        List<Map<String, Object>> out = service().pendingSigns("uu_1");
        assertEquals(1, out.size());
        assertEquals("ur_wait", out.get(0).get("id"));
    }

    /** 报修：落到急修单并派给该梯责任人（按 workerPlatformId 找员工） */
    @Test
    void reportFaultCreatesAndDispatches() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", "wp_1")));
        when(employeeMapper.selectList(any())).thenReturn(List.of(employee("emp_w", "wp_1")));
        when(directoryService.createFault(any(), anyString()))
                .thenReturn(Map.of("id", "ft_new", "status", "OPEN"));
        when(directoryService.dispatchFault(eq("ft_new"), eq("emp_w")))
                .thenReturn(Map.of("id", "ft_new", "status", "ASSIGNED"));

        Map<String, Object> out = service().reportFault("uu_1",
                Map.of("elevatorCode", "EM-1", "desc", "困人", "faultType", "困人（紧急）"));

        assertEquals("ASSIGNED", out.get("status"));
        verify(directoryService).dispatchFault("ft_new", "emp_w");
        // 登记人带 unit: 前缀，供员工端区分“使用单位报修”
        verify(directoryService).createFault(any(), org.mockito.ArgumentMatchers.startsWith("unit:uu_1"));
    }

    /** 无责任人：只建单不派单（留 OPEN 待管理端调度） */
    @Test
    void reportFaultWithoutWorkerStaysOpen() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        when(directoryService.createFault(any(), anyString())).thenReturn(Map.of("id", "ft_new", "status", "OPEN"));

        Map<String, Object> out = service().reportFault("uu_1", Map.of("elevatorCode", "EM-1", "desc", "异响"));
        assertEquals("OPEN", out.get("status"));
        verify(directoryService, never()).dispatchFault(anyString(), anyString());
    }

    /** 报修别人单位的电梯 → 1403（不落单、不派单） */
    @Test
    void reportFaultRejectsForeignElevator() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_2", "EM-2", "uu_2", null)));
        BizException ex = assertThrows(BizException.class,
                () -> service().reportFault("uu_1", Map.of("elevatorCode", "EM-2", "desc", "异响")));
        assertEquals(1403, ex.getCode());
        verify(directoryService, never()).createFault(any(), anyString());
    }

    /** 缺电梯/描述 → 422 */
    @Test
    void reportFaultValidatesRequiredFields() {
        BizException noElevator = assertThrows(BizException.class,
                () -> service().reportFault("uu_1", Map.of("desc", "异响")));
        assertEquals(422, noElevator.getCode());
        BizException noDesc = assertThrows(BizException.class,
                () -> service().reportFault("uu_1", Map.of("elevatorCode", "EM-1")));
        assertEquals(422, noDesc.getCode());
        assertTrue(noDesc.getMessage().contains("描述"));
    }

    private static Employee employee(String id, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = "张伟";
        e.platformId = platformId;
        return e;
    }
}
