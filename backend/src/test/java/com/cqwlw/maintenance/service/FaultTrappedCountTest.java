package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 急修单困人人数（C-18/V62 落地）：
 * 建单接收 trappedCount（0-99，越界 422）→ faultView 下发（缺省 0）→ 列表 trapped=1 过滤仅困人单。
 * 工作台「急修待接」KPI / 困人横幅 / order/list?state=urgent 三处共用此口径。
 */
class FaultTrappedCountTest {

    private DirectoryService svc;
    private FaultMapper faults;
    private WorkOrderService workOrderService;

    @BeforeEach
    void setUp() {
        faults = mock(FaultMapper.class);
        workOrderService = mock(WorkOrderService.class);
        ElevatorMapper elevators = mock(ElevatorMapper.class);
        EmployeeMapper employees = mock(EmployeeMapper.class);
        svc = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                elevators, mock(ChecklistService.class), workOrderService,
                mock(EmployeeScopeService.class), employees, mock(UseUnitMapper.class),
                mock(WorkOrderMapper.class), mock(CompanyMapper.class), mock(FileStorageService.class),
                mock(TenantSnapshotService.class));
        when(faults.selectCount(any())).thenReturn(0L);
        // mock 默认返回 null：firstCheckinToday / enrichFaultViews 的 selectList 需要空集兜底
        when(elevators.selectList(any())).thenReturn(List.of());
        when(employees.selectList(any())).thenReturn(List.of());
    }

    /** 建单：trappedCount 入单且视图下发；缺省为 0 */
    @Test
    void createFaultCarriesTrappedCount() {
        Map<String, Object> v = svc.createFault(Map.of(
                "elevatorCode", "EM-1", "desc", "轿厢困人两人", "faultType", "困人（紧急）", "trappedCount", 2), "e1");
        assertEquals(2, v.get("trappedCount"));

        Map<String, Object> v0 = svc.createFault(Map.of("elevatorCode", "EM-1", "desc", "层门关门异响"), "e1");
        assertEquals(0, v0.get("trappedCount"));
    }

    /** 建单校验：困人人数越界 422 */
    @Test
    void createFaultRejectsInvalidTrappedCount() {
        BizException e = assertThrows(BizException.class, () -> svc.createFault(Map.of(
                "elevatorCode", "EM-1", "desc", "轿厢困人", "trappedCount", -1), "e1"));
        assertEquals(422, e.getCode());
        assertThrows(BizException.class, () -> svc.createFault(Map.of(
                "elevatorCode", "EM-1", "desc", "轿厢困人", "trappedCount", 100), "e1"));
    }

    /** 列表 trapped=1 过滤：仅困人单（trappedCount>0），普单与 0 人单排除 */
    @Test
    @SuppressWarnings("unchecked")
    void listFaultsTrappedFilter() {
        Fault trapped = fault("f_t", 3);
        Fault zero = fault("f_z", 0);
        Fault legacy = fault("f_l", null); // 历史行 NULL 视为非困人
        when(faults.selectList(any())).thenReturn(List.of(trapped, zero, legacy));
        when(workOrderService.paginate(any(), any())).thenAnswer((inv) -> {
            List<Map<String, Object>> views = inv.getArgument(0);
            return Map.of("list", views, "total", views.size());
        });

        Map<String, Object> all = svc.listFaults(Map.of("page", "1", "size", "20", "status", "", "trapped", ""), "e1");
        assertEquals(3, all.get("total"));
        Map<String, Object> only = svc.listFaults(Map.of("page", "1", "size", "20", "status", "", "trapped", "1"), "e1");
        assertEquals(1, only.get("total"));
        assertEquals("f_t", ((List<Map<String, Object>>) only.get("list")).get(0).get("id"));
        // trapped=0：仅非困人单（含历史 NULL 行），与「报修待接」KPI 口径一致
        Map<String, Object> rest = svc.listFaults(Map.of("page", "1", "size", "20", "status", "", "trapped", "0"), "e1");
        assertEquals(2, rest.get("total"));
    }

    private static Fault fault(String id, Integer trappedCount) {
        Fault f = new Fault();
        f.id = id;
        f.status = "OPEN";
        f.elevatorCode = "EM-1";
        f.trappedCount = trappedCount;
        return f;
    }
}
