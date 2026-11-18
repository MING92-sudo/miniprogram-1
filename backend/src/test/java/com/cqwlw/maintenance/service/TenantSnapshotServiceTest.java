package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户快照服务（权限 P2 T3）：派单/排班工单冻结 useUnitId/useUnitName；
 * 过户嫌疑按 biz_elevator_change 的 CONFIRMED TRANSFER 行判定；unitNameOf 空值收敛 null。
 */
class TenantSnapshotServiceTest {

    private UseUnitMapper units;
    private BizElevatorChangeMapper changes;
    private TenantSnapshotService svc;

    /** 条件查询的 LambdaQueryWrapper 列解析需 TableInfo 缓存（MigrationIssueServiceTest 同款先例） */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), BizElevatorChange.class);
    }

    @BeforeEach
    void setUp() {
        units = mock(UseUnitMapper.class);
        changes = mock(BizElevatorChangeMapper.class);
        svc = new TenantSnapshotService(units, changes, mock(MigrationIssueService.class));
    }

    private static UseUnit unit(String name) {
        UseUnit u = new UseUnit();
        u.id = "uu1";
        u.unitName = name;
        return u;
    }

    private static Elevator elevator(String useUnitId) {
        Elevator el = new Elevator();
        el.id = "e1";
        el.useUnitId = useUnitId;
        return el;
    }

    @Test
    void applyOrderCopiesUnitIdAndResolvesName() {
        when(units.selectById("uu1")).thenReturn(unit("启元物业"));
        WorkOrder o = new WorkOrder();
        svc.applyOrder(o, elevator("uu1"));
        assertEquals("uu1", o.useUnitId);
        assertEquals("启元物业", o.useUnitName);
    }

    @Test
    void applyOrderWithoutUnitLeavesNulls() {
        WorkOrder o = new WorkOrder();
        o.useUnitId = "stale";
        o.useUnitName = "staleName";
        svc.applyOrder(o, elevator(null));
        assertNull(o.useUnitId);
        assertNull(o.useUnitName);
        verify(units, never()).selectById(any());
    }

    @Test
    void applyOrderUnresolvedUnitKeepsIdAndNullName() {
        when(units.selectById("uu_missing")).thenReturn(null);
        WorkOrder o = new WorkOrder();
        svc.applyOrder(o, elevator("uu_missing"));
        assertEquals("uu_missing", o.useUnitId);
        assertNull(o.useUnitName);
    }

    @Test
    void applyFaultSameSemantics() {
        when(units.selectById("uu1")).thenReturn(unit("启元物业"));
        Fault f = new Fault();
        svc.applyFault(f, elevator("uu1"));
        assertEquals("uu1", f.useUnitId);
        assertEquals("启元物业", f.useUnitName);
    }

    @Test
    void transferSuspectDetectedByConfirmedTransferRow() {
        when(changes.selectCount(any())).thenReturn(1L);
        assertTrue(svc.isTransferSuspect("e1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<BizElevatorChange>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(changes).selectCount(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("change_type"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("elevator_id"), sql);
    }

    @Test
    void transferSuspectFalseWhenNoConfirmedTransfer() {
        when(changes.selectCount(any())).thenReturn(0L);
        assertFalse(svc.isTransferSuspect("e1"));
    }

    @Test
    void unitNameOfBlankReturnsNull() {
        assertNull(svc.unitNameOf(null));
        assertNull(svc.unitNameOf(""));
        assertNull(svc.unitNameOf("   "));
        verify(units, never()).selectById(any());
    }

    @Test
    void unitNameOfUnknownUnitReturnsNull() {
        when(units.selectById("uu_missing")).thenReturn(null);
        assertNull(svc.unitNameOf("uu_missing"));
    }
}
