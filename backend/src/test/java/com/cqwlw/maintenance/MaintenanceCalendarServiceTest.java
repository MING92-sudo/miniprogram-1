package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.MaintenanceCalendarService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 维保日历投影（Task 11）：从首保日（首次 HM）起算，第 15 天半月、90 天季度、180 天半年、365 天年度；
 * 只读投影不落库，窗口外不返回。
 */
class MaintenanceCalendarServiceTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 16);

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(MaintenanceCalendarService svc,
                                                     LocalDate from, LocalDate to) {
        Map<String, Object> result = svc.calendar("emp_1", from, to);
        return (List<Map<String, Object>>) result.get("entries");
    }

    private static MaintenanceCalendarService service() {
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);

        Employee emp = new Employee();
        emp.id = "emp_1";
        emp.name = "张伟";
        emp.platformId = "wp_1";
        when(employeeMapper.selectById("emp_1")).thenReturn(emp);

        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorCode = "EM-2026-001";
        el.elevatorName = "锦绣花园 1# 梯";
        el.workerPlatformId = "wp_1";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));

        WorkOrder first = new WorkOrder();
        first.id = "wo_first";
        first.elevatorId = "el_1";
        first.workTypeCode = "HM";
        first.planTime = LocalDateTime.of(2026, 1, 16, 9, 0);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(first));
        return new MaintenanceCalendarService(elevatorMapper, employeeMapper, workOrderMapper);
    }

    @Test
    void projectsQuarterlyAndSemiannual() {
        MaintenanceCalendarService svc = service();
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 31));

        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 1, 31)));   // 首保 +15
        assertEquals("TM", typeOn(rows, LocalDate.of(2026, 4, 16)));   // +90 季度（累加式取最高）
        assertEquals("SM", typeOn(rows, LocalDate.of(2026, 7, 15)));   // +180 半年
        assertEquals("OY", typeOn(rows, LocalDate.of(2027, 1, 16)));   // +365 年度
        // 非 15 倍数不排期
        assertTrue(rows.stream().noneMatch(r -> "2026-02-03".equals(r.get("date"))));
    }

    @Test
    void windowIsRespected() {
        MaintenanceCalendarService svc = service();
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        assertEquals(2, rows.size()); // 首保 01-16 + 01-31，其余在窗口外
        assertEquals("2026-01-16", rows.get(0).get("date"));
        assertEquals("HM", rows.get(0).get("workTypeCode"));
    }

    private static String typeOn(List<Map<String, Object>> rows, LocalDate date) {
        return rows.stream()
                .filter(r -> date.toString().equals(r.get("date")))
                .map(r -> String.valueOf(r.get("workTypeCode")))
                .findFirst().orElseThrow(() -> new AssertionError("未投影出 " + date));
    }
}
