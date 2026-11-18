package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户快照存量幂等回填 Runner 单测（权限 P2 T6；spec §6 / 裁决 R4/R6）。
 * 全 mock mapper + MigrationIssueService，直接 new Runner（@PostConstruct 在纯单测中不会被 Spring 调用）。
 */
class TenantSnapshotBackfillRunnerTest {

    private MaintainRecordMapper records;
    private FaultMapper faults;
    private WorkOrderMapper orders;
    private ElevatorMapper elevators;
    private UseUnitMapper units;
    private BizElevatorChangeMapper changes;
    private MigrationIssueService issues;
    private TenantSnapshotBackfillRunner runner;

    /** LambdaQueryWrapper 列解析依赖 TableInfo 缓存（MigrationIssueServiceTest/TenantSnapshotServiceTest 同款先例） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : List.of(WorkOrder.class, MaintainRecord.class, Fault.class,
                Elevator.class, BizElevatorChange.class, UseUnit.class)) {
            TableInfoHelper.initTableInfo(assistant, c);
        }
    }

    @BeforeEach
    void setUp() {
        records = mock(MaintainRecordMapper.class);
        faults = mock(FaultMapper.class);
        orders = mock(WorkOrderMapper.class);
        elevators = mock(ElevatorMapper.class);
        units = mock(UseUnitMapper.class);
        changes = mock(BizElevatorChangeMapper.class);
        issues = mock(MigrationIssueService.class);
        runner = new TenantSnapshotBackfillRunner(records, faults, orders, elevators, units, changes, issues);
    }

    // ---------- helpers ----------

    private static Elevator elevator(String id, String code, String unitId) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = code;
        el.useUnitId = unitId;
        return el;
    }

    private static UseUnit unit(String id, String name) {
        UseUnit u = new UseUnit();
        u.id = id;
        u.unitName = name;
        return u;
    }

    private static WorkOrder order(String id, String elevatorId) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.elevatorId = elevatorId;
        return o;
    }

    private static MaintainRecord record(String id, String code, String workOrderId) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = code;
        r.workOrderId = workOrderId;
        return r;
    }

    private static Fault fault(String id, String code) {
        Fault f = new Fault();
        f.id = id;
        f.elevatorCode = code;
        return f;
    }

    private static BizElevatorChange confirmedTransfer(String elevatorId) {
        BizElevatorChange c = new BizElevatorChange();
        c.elevatorId = elevatorId;
        c.changeType = "TRANSFER";
        c.status = "CONFIRMED";
        return c;
    }

    // ---------- ORDER ----------

    @Test
    void backfillsOrderFromElevatorUnit() {
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o1", "e1"))), new ArrayList<>());
        when(elevators.selectById("e1")).thenReturn(elevator("e1", "c1", "uu1"));
        when(units.selectById("uu1")).thenReturn(unit("uu1", "启元物业"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.orders);
        ArgumentCaptor<WorkOrder> cap = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orders).updateById(cap.capture());
        WorkOrder saved = cap.getValue();
        assertEquals("o1", saved.id);
        assertEquals("uu1", saved.useUnitId);
        assertEquals("启元物业", saved.useUnitName);
        // R6：只写 id + 快照列，其余从 DB 读出的列一律不回写
        assertNull(saved.elevatorId);
        assertNull(saved.orderNo);
        assertNull(saved.workType);
        verify(issues, never()).upsert(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString());
    }

    @Test
    void orderTransferSuspectNotBackfilled() {
        when(changes.selectList(any())).thenReturn(new ArrayList<>(List.of(confirmedTransfer("e1"))));
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o1", "e1"))), new ArrayList<>());
        when(elevators.selectById("e1")).thenReturn(elevator("e1", "c1", "uuNew"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.orders);
        verify(orders, never()).updateById(any(WorkOrder.class));
        verify(issues).upsert(eq("ORDER"), eq("o1"), eq("TRANSFER_SUSPECT"),
                eq("c1"), isNull(), eq("uuNew"), anyString(), eq("LOW"));
    }

    @Test
    void orderUnmatchedIssuesLow() {
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o1", "missing"))), new ArrayList<>());
        when(elevators.selectById("missing")).thenReturn(null);

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.orders);
        verify(orders, never()).updateById(any(WorkOrder.class));
        verify(issues).upsert(eq("ORDER"), eq("o1"), eq("UNMATCHED"),
                isNull(), isNull(), isNull(), anyString(), eq("LOW"));
    }

    // ---------- RECORD ----------

    @Test
    void transferSuspectNotBackfilledAndIssued() {
        when(changes.selectList(any())).thenReturn(new ArrayList<>(List.of(confirmedTransfer("es"))));
        when(records.selectList(any())).thenReturn(
                new ArrayList<>(List.of(record("ur1", "susp", null))), new ArrayList<>());
        when(elevators.selectList(any())).thenReturn(
                new ArrayList<>(List.of(elevator("es", "susp", "uuS"))));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.records);
        assertEquals(1, rep.issues);
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(issues).upsert(eq("RECORD"), eq("ur1"), eq("TRANSFER_SUSPECT"),
                eq("susp"), isNull(), eq("uuS"), anyString(), eq("LOW"));
    }

    @Test
    void recordInheritsWorkOrderSnapshotFirst() {
        WorkOrder wo = order("wo1", "e-wo");
        wo.useUnitId = "uu2";
        wo.useUnitName = "uu2单位";
        when(records.selectList(any())).thenReturn(
                new ArrayList<>(List.of(record("ur1", "code-x", "wo1"))), new ArrayList<>());
        when(orders.selectById("wo1")).thenReturn(wo);

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.records);
        ArgumentCaptor<MaintainRecord> cap = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(records).updateById(cap.capture());
        MaintainRecord saved = cap.getValue();
        assertEquals("uu2", saved.useUnitId);
        assertEquals("uu2单位", saved.useUnitName);
        // R4：工单继承路径 elevator_id 取 o.elevatorId
        assertEquals("e-wo", saved.elevatorId);
        // R6：只写 id + 快照列
        assertNull(saved.elevatorCode);
        assertNull(saved.workOrderId);
        assertNull(saved.workType);
        // 工单继承路径优先，不查电梯码
        verify(elevators, never()).selectList(any());
        verify(units, never()).selectById(any());
    }

    @Test
    void codeAmbiguousTakesFirstRowByIdAndIssues() {
        when(records.selectList(any())).thenReturn(
                new ArrayList<>(List.of(record("ur1", "dup", null))), new ArrayList<>());
        when(elevators.selectList(any())).thenReturn(new ArrayList<>(List.of(
                elevator("e1", "dup", "uuA"), elevator("e2", "dup", "uuB"))));
        when(units.selectById("uuA")).thenReturn(unit("uuA", "e1对应单位"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.records);
        ArgumentCaptor<MaintainRecord> cap = ArgumentCaptor.forClass(MaintainRecord.class);
        verify(records).updateById(cap.capture());
        MaintainRecord saved = cap.getValue();
        assertEquals("uuA", saved.useUnitId);
        assertEquals("e1对应单位", saved.useUnitName);
        // R4：电梯码路径 elevator_id 取首行 el.id（ORDER BY id 的 e1）
        assertEquals("e1", saved.elevatorId);
        // R6
        assertNull(saved.elevatorCode);
        assertNull(saved.workOrderId);
        verify(issues).upsert(eq("RECORD"), eq("ur1"), eq("CODE_AMBIGUOUS"),
                eq("dup"), isNull(), eq("uuA"), anyString(), eq("MID"));
    }

    @Test
    void unmatchedElevatorLeavesNullAndIssues() {
        when(records.selectList(any())).thenReturn(
                new ArrayList<>(List.of(record("ur1", "none", null))), new ArrayList<>());
        when(elevators.selectList(any())).thenReturn(new ArrayList<>());

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.records);
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(issues).upsert(eq("RECORD"), eq("ur1"), eq("UNMATCHED"),
                eq("none"), isNull(), isNull(), anyString(), eq("LOW"));
    }

    // ---------- FAULT ----------

    @Test
    void faultBackfilledFromElevatorCode() {
        when(faults.selectList(any())).thenReturn(
                new ArrayList<>(List.of(fault("f1", "cf"))), new ArrayList<>());
        when(elevators.selectList(any())).thenReturn(
                new ArrayList<>(List.of(elevator("eF", "cf", "uuF"))));
        when(units.selectById("uuF")).thenReturn(unit("uuF", "急修单位"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.faults);
        ArgumentCaptor<Fault> cap = ArgumentCaptor.forClass(Fault.class);
        verify(faults).updateById(cap.capture());
        Fault saved = cap.getValue();
        assertEquals("f1", saved.id);
        assertEquals("uuF", saved.useUnitId);
        assertEquals("急修单位", saved.useUnitName);
        // R6
        assertNull(saved.elevatorCode);
        assertNull(saved.faultNo);
        assertNull(saved.descr);
        verify(issues, never()).upsert(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString());
    }

    @Test
    void faultCodeAmbiguousIssues() {
        when(faults.selectList(any())).thenReturn(
                new ArrayList<>(List.of(fault("f1", "dup"))), new ArrayList<>());
        when(elevators.selectList(any())).thenReturn(new ArrayList<>(List.of(
                elevator("e1", "dup", "uuA"), elevator("e2", "dup", "uuB"))));
        when(units.selectById("uuA")).thenReturn(unit("uuA", "e1对应单位"));

        runner.backfill();

        ArgumentCaptor<Fault> cap = ArgumentCaptor.forClass(Fault.class);
        verify(faults).updateById(cap.capture());
        assertEquals("uuA", cap.getValue().useUnitId);
        verify(issues).upsert(eq("FAULT"), eq("f1"), eq("CODE_AMBIGUOUS"),
                eq("dup"), isNull(), eq("uuA"), anyString(), eq("MID"));
    }

    // ---------- 幂等 / 分页 / 异常防御 ----------

    @Test
    void secondRunIsNoop() {
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o1", "e1"))), new ArrayList<>());
        when(elevators.selectById("e1")).thenReturn(elevator("e1", "c1", "uu1"));
        when(units.selectById("uu1")).thenReturn(unit("uu1", "启元物业"));

        TenantSnapshotBackfillRunner.Report first = runner.backfill();
        assertEquals(1, first.orders);
        verify(orders, times(1)).updateById(any(WorkOrder.class));

        TenantSnapshotBackfillRunner.Report second = runner.backfill();
        assertEquals(0, second.orders);
        // 第二次运行不产生任何新 update / insert（upsert）
        verify(orders, times(1)).updateById(any(WorkOrder.class));
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(faults, never()).updateById(any(Fault.class));
        verify(issues, never()).upsert(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString());
    }

    @Test
    void processesAllBatchesUntilEmpty() {
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o1", "e1"))),
                new ArrayList<>(List.of(order("o2", "e2"))),
                new ArrayList<>());
        when(elevators.selectById("e1")).thenReturn(elevator("e1", "c1", "uu1"));
        when(elevators.selectById("e2")).thenReturn(elevator("e2", "c2", "uu2"));
        when(units.selectById("uu1")).thenReturn(unit("uu1", "启元物业"));
        when(units.selectById("uu2")).thenReturn(unit("uu2", "启元二物业"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(2, rep.orders);
        verify(orders, times(2)).updateById(any(WorkOrder.class));
    }

    @Test
    void badRowDoesNotStopBatch() {
        when(orders.selectList(any())).thenReturn(
                new ArrayList<>(List.of(order("o-bad", "boom"), order("o-good", "e1"))),
                new ArrayList<>());
        when(elevators.selectById("boom")).thenThrow(new RuntimeException("db glitch"));
        when(elevators.selectById("e1")).thenReturn(elevator("e1", "c1", "uu1"));
        when(units.selectById("uu1")).thenReturn(unit("uu1", "启元物业"));

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.orders);
        ArgumentCaptor<WorkOrder> cap = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orders).updateById(cap.capture());
        assertEquals("o-good", cap.getValue().id);
    }

    // ---------- 回归：keyset 游标分页消除 issue-only 行死循环（C-1 / R12） ----------

    /**
     * issue-only 行（UNMATCHED，use_unit_id 恒 null）在旧实现会被 while(true) 反复取回 → 死循环。
     * 本用例用 thenAnswer 计数（首批返回该行、之后空批）保证有限终止；再用 captor 钉住
     * “第二次查询带 gt(id,lastId) 且游标值为上批末元素”——旧实现无 gt → RED。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void cursorSkipsIssueOnlyRowsNoInfiniteLoop() {
        final int[] calls = {0};
        when(orders.selectList(any())).thenAnswer(inv -> {
            if (calls[0]++ == 0) {
                return new ArrayList<>(List.of(order("o1", "missing")));
            }
            return new ArrayList<WorkOrder>();
        });
        when(elevators.selectById("missing")).thenReturn(null);

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.orders);
        assertEquals(1, rep.issues); // UNMATCHED 留痕，行仍 use_unit_id IS NULL

        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(orders, times(2)).selectList(cap.capture()); // 必须发生第二次查询（游标推进后重查）
        Wrapper first = cap.getAllValues().get(0);
        Wrapper second = cap.getAllValues().get(1);
        assertFalse(first.getSqlSegment().contains(">"), "首批不应带游标");
        assertTrue(second.getSqlSegment().contains(">"), "第二批必须带 gt(id,lastId) 游标推进");
        assertTrue(((AbstractWrapper) second).getParamNameValuePairs().containsValue("o1"),
                "游标值必须是上批末元素 id=o1");
    }

    /** RECORD / FAULT 两处循环同样必须以游标推进，避免 issue-only 行死循环。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void recordAndFaultLoopsAdvanceCursorPastIssueOnlyRows() {
        final int[] recordCalls = {0};
        when(records.selectList(any())).thenAnswer(inv -> {
            if (recordCalls[0]++ == 0) {
                return new ArrayList<>(List.of(record("ur1", "none", null)));
            }
            return new ArrayList<MaintainRecord>();
        });
        final int[] faultCalls = {0};
        when(faults.selectList(any())).thenAnswer(inv -> {
            if (faultCalls[0]++ == 0) {
                return new ArrayList<>(List.of(fault("f1", "none")));
            }
            return new ArrayList<Fault>();
        });
        when(elevators.selectList(any())).thenReturn(new ArrayList<>());

        TenantSnapshotBackfillRunner.Report rep = runner.backfill();

        assertEquals(1, rep.records);
        assertEquals(1, rep.faults);
        assertEquals(2, rep.issues);

        ArgumentCaptor<Wrapper> recCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(records, times(2)).selectList(recCap.capture());
        assertTrue(recCap.getAllValues().get(1).getSqlSegment().contains(">"),
                "RECORD 第二批必须带游标");
        assertTrue(((AbstractWrapper) recCap.getAllValues().get(1)).getParamNameValuePairs().containsValue("ur1"),
                "RECORD 游标值必须是上批末元素 id=ur1");

        ArgumentCaptor<Wrapper> faultCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(faults, times(2)).selectList(faultCap.capture());
        assertTrue(faultCap.getAllValues().get(1).getSqlSegment().contains(">"),
                "FAULT 第二批必须带游标");
        assertTrue(((AbstractWrapper) faultCap.getAllValues().get(1)).getParamNameValuePairs().containsValue("f1"),
                "FAULT 游标值必须是上批末元素 id=f1");
    }
}
