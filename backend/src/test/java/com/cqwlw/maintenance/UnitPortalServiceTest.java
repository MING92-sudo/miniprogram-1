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
import static org.mockito.Mockito.atMost;
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
        return record(id, code, "uu_1");
    }

    /** P2b T9：客户读路径改按记录快照 useUnitId 过滤，fixture 须显式钉住快照列 */
    private static MaintainRecord record(String id, String code, String useUnitId) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = code;
        r.elevatorName = code + " 梯";
        r.workType = "半月维保";
        r.confirmStatus = "PENDING";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.version = 1;
        r.useUnitId = useUnitId;
        return r;
    }

    private static Fault fault(String id, String code) {
        return fault(id, code, "uu_1");
    }

    /** P2b T9：急修读路径改按 Fault 快照 useUnitId 过滤，fixture 须显式钉住快照列 */
    private static Fault fault(String id, String code, String useUnitId) {
        Fault f = new Fault();
        f.id = id;
        f.elevatorCode = code;
        f.status = "OPEN";
        f.descr = "门关不上";
        f.useUnitId = useUnitId;
        return f;
    }

    /** 其他单位的电梯/记录/急修必须被过滤掉（含越权读） */
    @Test
    void scopesToOwnUnit() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(
                elevator("el_1", "EM-1", "uu_1", null),
                elevator("el_2", "EM-2", "uu_2", null)));
        when(recordMapper.selectList(any())).thenReturn(List.of(
                record("ur_1", "EM-1", "uu_1"), record("ur_2", "EM-2", "uu_2")));
        when(faultMapper.selectList(any())).thenReturn(List.of(
                fault("ft_1", "EM-1", "uu_1"), fault("ft_2", "EM-2", "uu_2")));

        UnitPortalService svc = service();
        assertEquals(1, svc.elevators("uu_1").size());
        assertEquals("EM-1", svc.elevators("uu_1").get(0).get("elevatorCode"));
        assertEquals(1, svc.records("uu_1").size());
        assertEquals("EM-1", svc.records("uu_1").get(0).get("elevatorCode"));
        assertEquals(1, svc.faults("uu_1").size());
        assertEquals("EM-1", svc.faults("uu_1").get(0).get("elevatorCode"));
    }

    /**
     * C-25②（投影补齐）：Fault 表无 elevatorName 列，急修行电梯名按 elevatorCode 批量联本单位电梯
     * （一次 .in 查询建 code→name，查询次数不随急修行数增长 = 无 N+1）；
     * 缺名回退 elevatorCode（与 pkg-client orders 页 `item.elevatorName || item.elevatorCode` 同显示语义）；
     * 跨单位过滤口径不变。trapped 依赖 C-18 增列，不改表裁决，本投影暂不下发。
     */
    @Test
    void faultsCarryElevatorNameFromBatchJoin() {
        Elevator nameless = elevator("el_2", "EM-2", "uu_1", null);
        nameless.elevatorName = null; // 档案缺名：投影回退设备码
        when(elevatorMapper.selectList(any())).thenReturn(List.of(
                elevator("el_1", "EM-1", "uu_1", null),
                nameless,
                elevator("el_9", "EM-9", "uu_2", null))); // 他单位：被过滤，名称不得进入本单位行
        when(faultMapper.selectList(any())).thenReturn(List.of(
                fault("ft_1", "EM-1", "uu_1"), fault("ft_2", "EM-1", "uu_1"),
                fault("ft_3", "EM-2", "uu_1"), fault("ft_4", "EM-9", "uu_2")));

        List<Map<String, Object>> out = service().faults("uu_1");

        // 跨单位急修仍被过滤（ft_4/EM-9 非 uu_1 名下电梯）
        assertEquals(List.of("ft_1", "ft_2", "ft_3"), out.stream().map(r -> r.get("id")).toList());
        assertEquals("EM-1 梯", out.get(0).get("elevatorName"));
        assertEquals("EM-1 梯", out.get(1).get("elevatorName"));
        assertEquals("EM-2", out.get(2).get("elevatorName"));
        // 无 N+1（P2 T5/T3 minor）：电梯表只查 1 次——codes 与电梯名 map 复用同一次 ownElevators 结果，
        // 不再按 codes 重查电梯；更不得逐行查
        verify(elevatorMapper, atMost(1)).selectList(any());
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
        MaintainRecord nextOfOld = record("ur_old_v2", "EM-1");
        nextOfOld.prevVersionId = "ur_old"; // IN(prev_version_id) 批量查询命中的后续版本行
        when(recordMapper.selectList(any()))
                .thenReturn(List.of(review, dual, legacy, sign, signed, archived, refused, old))
                .thenReturn(List.of(nextOfOld));

        List<Map<String, Object>> out = service().records("uu_1");
        assertEquals(List.of("ur_sign", "ur_signed", "ur_arch", "ur_ref"),
                out.stream().map(r -> r.get("id")).toList());
        assertEquals("PENDING_CLIENT_SIGNATURE", out.get(0).get("lifecycleStatus"));
        assertEquals(1, out.get(0).get("version"));
        // P2 T5（N+1 消除）：最新版本判定批量化——记录表至多 2 次查询（列表 1 + supersededIds 1），不再逐条 selectCount
        verify(recordMapper, never()).selectCount(any());
        verify(recordMapper, atMost(2)).selectList(any());
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

    /**
     * P1 T8-A1（控制器裁决 T7-C2，docs/14 §4.6 规则 8 兼容期）：
     * 仅 /unit/pending-signs 行保留 shareToken（小程序待办「去签字」以行内 token 拼签字链接，
     * sign 页只有 token 路径、无会话回退）；/unit/records/list 行一律不含（§4.7 白名单）。
     * 与 mock clientPendingSignRow/clientRecordRow 口径对齐。
     */
    @Test
    void pendingSignsCarryShareTokenWhileRecordsDoNot() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        MaintainRecord wait = record("ur_wait", "EM-1");
        wait.shareToken = "sg-compat-1";
        MaintainRecord signed = record("ur_signed", "EM-1");
        signed.lifecycleStatus = "SIGNED";
        signed.confirmStatus = "CONFIRMED";
        signed.shareToken = "sg-compat-2";
        when(recordMapper.selectList(any())).thenReturn(List.of(wait, signed));

        UnitPortalService svc = service();
        List<Map<String, Object>> pending = svc.pendingSigns("uu_1");
        assertEquals(1, pending.size());
        assertEquals("sg-compat-1", pending.get(0).get("shareToken"));
        List<Map<String, Object>> rows = svc.records("uu_1");
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            assertTrue(!row.containsKey("shareToken"), "records 列表行不应下发签字凭证 shareToken");
        }
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

    /**
     * P2b T9（快照谓词）：记录归属以 r.useUnitId 快照为准，不再看当前电梯归属。
     * r.elevatorCode=EM-1 属 uu_1 电梯，但快照 useUnitId=uuB → uuA 不含、uuB 含。
     */
    @Test
    void recordsFilteredBySnapshotUnitNotElevatorCode() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        when(recordMapper.selectList(any())).thenReturn(List.of(record("ur_snap_b", "EM-1", "uuB")));

        UnitPortalService svc = service();
        assertTrue(svc.records("uu_1").stream().noneMatch(r -> "ur_snap_b".equals(r.get("id"))),
                "快照属 uuB 的记录不得出现在 uuA 的客户列表");
        List<Map<String, Object>> b = svc.records("uuB");
        assertEquals(1, b.size());
        assertEquals("ur_snap_b", b.get(0).get("id"));
    }

    /**
     * P2b T9（NULL fail-closed）：use_unit_id 为 NULL 的历史记录对客户完全隐藏，
     * records() 与 pendingSigns() 两条读路径均不得下发。
     */
    @Test
    void nullSnapshotHiddenFromClient() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        when(recordMapper.selectList(any())).thenReturn(List.of(record("ur_null", "EM-1", null)));

        UnitPortalService svc = service();
        assertTrue(svc.records("uu_1").isEmpty(), "NULL 快照记录不得出现在客户记录列表");
        assertTrue(svc.pendingSigns("uu_1").isEmpty(), "NULL 快照记录不得出现在客户待签列表");
    }

    /**
     * P2b T9（快照谓词）：急修单归属以 f.useUnitId 快照为准，不再看当前电梯归属。
     * 电梯名 map 仍按本单位在保档案投影（缺名回退 elevatorCode 口径不变）。
     */
    @Test
    void faultsFilteredBySnapshotUnit() {
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator("el_1", "EM-1", "uu_1", null)));
        when(faultMapper.selectList(any())).thenReturn(List.of(fault("ft_snap_b", "EM-1", "uuB")));

        UnitPortalService svc = service();
        assertTrue(svc.faults("uu_1").isEmpty(), "快照属 uuB 的急修单不得出现在 uuA 的客户列表");
        List<Map<String, Object>> b = svc.faults("uuB");
        assertEquals(1, b.size());
        assertEquals("ft_snap_b", b.get(0).get("id"));
        assertEquals("EM-1", b.get(0).get("elevatorName"), "本单位无档案时缺名回退 elevatorCode");
    }

    private static Employee employee(String id, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = "张伟";
        e.platformId = platformId;
        return e;
    }
}
