package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.Rescue;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DirectoryService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 台账写接口必填校验（验收 F-1/F-2）：故障/救援/自检缺必填字段 422，防建空记录 */
class DirectoryServiceTest {

    private RescueMapper rescueMapper;
    private FaultMapper faultMapper;
    private InspectRecordMapper inspectMapper;
    private DirectoryService service;

    @BeforeEach
    void setUp() {
        rescueMapper = mock(RescueMapper.class);
        faultMapper = mock(FaultMapper.class);
        inspectMapper = mock(InspectRecordMapper.class);
        service = new DirectoryService(rescueMapper, faultMapper, mock(DrillMapper.class), inspectMapper,
                mock(MessageMapper.class), mock(KnowledgeMapper.class), mock(ElevatorMapper.class),
                mock(ChecklistService.class), mock(WorkOrderService.class), mock(EmployeeScopeService.class), mock(EmployeeMapper.class), mock(UseUnitMapper.class), mock(WorkOrderMapper.class));
    }

    @Test
    void createFaultRequiresElevatorCodeAndDesc() {
        assertThrows(BizException.class, () -> service.createFault(Map.of(), "emp_1"));
        assertEquals(422, code(() -> service.createFault(Map.of("desc", "x"), "emp_1")));
        assertEquals(422, code(() -> service.createFault(Map.of("elevatorCode", "EM-1"), "emp_1")));

        Map<String, Object> ok = service.createFault(Map.of("elevatorCode", "EM-2024-002", "desc", "厅门异响"), "emp_1");
        ArgumentCaptor<Fault> c = ArgumentCaptor.forClass(Fault.class);
        verify(faultMapper).insert(c.capture());
        assertEquals("EM-2024-002", c.getValue().elevatorCode);
        assertEquals("厅门异响", c.getValue().descr);
        assertEquals("emp_1", c.getValue().createdBy);
        assertEquals("OPEN", ok.get("status"));
    }

    @Test
    void createRescueRequiresElevatorAndTimes() {
        assertEquals(422, code(() -> service.createRescue(Map.of())));
        assertEquals(422, code(() -> service.createRescue(Map.of("elevatorCode", "EM-1"))));
        assertEquals(422, code(() -> service.createRescue(Map.of(
                "elevatorCode", "EM-1", "alarmAt", "2026-10-01 16:00:00"))));

        Map<String, Object> ok = service.createRescue(Map.of(
                "elevatorCode", "EM-2024-003", "alarmAt", "2026-10-01 16:00:00",
                "arriveAt", "2026-10-01 16:20:00", "trappedCount", 2));
        assertEquals(20, ok.get("arriveMinutes"));
        assertEquals(Boolean.FALSE, ok.get("overtime"));
    }

    @Test
    void createInspectRequiresItemsAndSignatures() {
        assertEquals(422, code(() -> service.createInspect(Map.of(
                "items", List.of(), "inspectorSign", "a", "reviewerSign", "b"))));
        assertEquals(422, code(() -> service.createInspect(Map.of(
                "items", List.of(Map.of("name", "x")), "inspectorSign", "a", "reviewerSign", "b"))));

        Map<String, Object> ok = service.createInspect(Map.of(
                "elevatorId", "el_1",
                "items", List.of(Map.of("name", "A-1-01 机房环境", "result", "NORMAL")),
                "inspectorSign", "sigA", "reviewerSign", "sigB"));
        assertEquals(1, ok.get("itemTotal"));
    }

    private static int code(Runnable r) {
        try {
            r.run();
            return 0;
        } catch (BizException e) {
            return e.getCode();
        }
    }
}
