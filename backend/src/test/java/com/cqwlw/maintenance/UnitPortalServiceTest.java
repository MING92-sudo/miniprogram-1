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

    /** T4-I5 fixture 同步：客户可见性按 §4.6 表过滤后，列表 fixture 须为可签状态才可见 */
    private static MaintainRecord record(String id, String code) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = code;
        r.elevatorName = code + " 梯";
        r.workType = "半月维保";
        r.confirmStatus = "PENDING";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.version = 1;
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

    /** 待签字 = 唯一可签状态（PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING）；已签（SIGNED/CONFIRMED）不列 */
    @Test
    void pendingSignsOnlyUnconfirmedOwnRecords() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        MaintainRecord confirmed = record("ur_done", "EM-1");
        confirmed.confirmStatus = "CONFIRMED";
        confirmed.lifecycleStatus = "SIGNED";
        when(recordMapper.selectList(any())).thenReturn(List.of(record("ur_wait", "EM-1"), confirmed));

        List<Map<String, Object>> out = service().pendingSigns("uu_1");
        assertEquals(1, out.size());
        assertEquals("ur_wait", out.get(0).get("id"));
    }

    /** T4-I5（docs/14 §4.6 可见性表）：客户列表只含 待签/已签/归档/拒签 四态 + 链末端最新版本；行投影带 lifecycleStatus/version */
    @Test
    void recordsFollowVisibilityTable() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        MaintainRecord review = record("ur_review", "EM-1");
        review.lifecycleStatus = "PENDING_REVIEW";
        MaintainRecord dual = record("ur_dual", "EM-1");
        dual.lifecycleStatus = "PENDING_CLIENT_SIGN";
        MaintainRecord legacy = record("ur_legacy", "EM-1");
        legacy.lifecycleStatus = null; // V42 前历史行：不作为客户可见记录
        MaintainRecord sign = record("ur_sign", "EM-1");
        MaintainRecord signed = record("ur_signed", "EM-1");
        signed.lifecycleStatus = "SIGNED";
        signed.confirmStatus = "CONFIRMED";
        MaintainRecord archived = record("ur_arch", "EM-1");
        archived.lifecycleStatus = "ARCHIVED";
        archived.confirmStatus = "CONFIRMED";
        MaintainRecord refused = record("ur_ref", "EM-1");
        refused.lifecycleStatus = "REFUSED_RETURNED";
        MaintainRecord old = record("ur_old", "EM-1"); // 旧版本：存在 prevVersionId=ur_old.id 的后续版本
        when(recordMapper.selectList(any()))
                .thenReturn(List.of(review, dual, legacy, sign, signed, archived, refused, old));
        when(recordMapper.selectCount(any())).thenReturn(0L, 0L, 0L, 0L, 1L);

        List<Map<String, Object>> out = service().records("uu_1");
        assertEquals(List.of("ur_sign", "ur_signed", "ur_arch", "ur_ref"),
                out.stream().map(r -> r.get("id")).toList());
        assertEquals("PENDING_CLIENT_SIGNATURE", out.get(0).get("lifecycleStatus"));
        assertEquals(1, out.get(0).get("version"));
    }

    /** T4-I5：待签列表剔除 双签未齐/已签/状态撕裂残留（PENDING_CLIENT_SIGNATURE+CONFIRMED） */
    @Test
    void pendingSignsOnlySignableState() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        MaintainRecord sign = record("ur_sign", "EM-1");
        MaintainRecord dual = record("ur_dual", "EM-1");
        dual.lifecycleStatus = "PENDING_CLIENT_SIGN";
        MaintainRecord signed = record("ur_signed", "EM-1");
        signed.lifecycleStatus = "SIGNED";
        signed.confirmStatus = "CONFIRMED";
        MaintainRecord torn = record("ur_torn", "EM-1"); // 归档异常残留：待签态但已确认
        torn.confirmStatus = "CONFIRMED";
        when(recordMapper.selectList(any())).thenReturn(List.of(sign, dual, signed, torn));

        List<Map<String, Object>> out = service().pendingSigns("uu_1");
        assertEquals(1, out.size());
        assertEquals("ur_sign", out.get(0).get("id"));
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
