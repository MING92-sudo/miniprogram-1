package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.EmployeeScopeService;
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

    /** 管理角色语义（ids=null，不做区域过滤）——投影逻辑用例共用 */
    private static EmployeeScopeService unrestrictedScope() {
        EmployeeScopeService s = mock(EmployeeScopeService.class);
        when(s.visibleReadPlatformIds(any())).thenReturn(null);
        return s;
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
        return new MaintenanceCalendarService(elevatorMapper, employeeMapper, workOrderMapper, unrestrictedScope());
    }

    @Test
    void projectsQuarterlyAndSemiannual() {
        MaintenanceCalendarService svc = service();
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 31));

        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 1, 31)));   // 首保 +15
        assertEquals("TM", typeOn(rows, LocalDate.of(2026, 4, 16)));   // +90 季度（累加式取最高）
        assertEquals("SM", typeOn(rows, LocalDate.of(2026, 7, 15)));   // +180 半年
        // 2026-11-21 修复：年度=首个 ≥365 天的格点（+375，2027-01-26），与派单 elapsedDays≥365 同口径，
        // 不再孤立在 15 天节奏格外（旧 typeAt 在 +365=2027-01-16 出格点外条目）
        assertEquals("OY", typeOn(rows, LocalDate.of(2027, 1, 26)));
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

    /** 建档即排期（2026-11-20）：无 HM 工单/无移交归档/无下次到期时，
     *  锚点回退「建档基线 lastMaintenanceAt + intervalDays」，直建电梯建档当日起即可投影全年 */
    @Test
    void anchorFallsBackToArchiveBaseline() {
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        Employee emp = new Employee();
        emp.id = "emp_1";
        emp.name = "张伟";
        emp.platformId = "wp_1";
        when(employeeMapper.selectById("emp_1")).thenReturn(emp);
        Elevator el = new Elevator();
        el.id = "el_2";
        el.elevatorCode = "EM-2026-002";
        el.elevatorName = "新直建梯";
        el.workerPlatformId = "wp_1";
        el.intervalDays = 15;
        el.lastMaintenanceAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        MaintenanceCalendarService svc = new MaintenanceCalendarService(
                elevatorMapper, employeeMapper, workOrderMapper, unrestrictedScope());
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 2, 1));
        assertEquals("2026-01-16", rows.get(0).get("date")); // 基线 01-01 + 15 天 = 首保锚点
        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 1, 31)));
        assertEquals("TM", typeOn(rows, LocalDate.of(2026, 4, 16)));
        assertEquals("SM", typeOn(rows, LocalDate.of(2026, 7, 15)));
        assertEquals("OY", typeOn(rows, LocalDate.of(2027, 1, 26))); // +375 首个 ≥365 格点
    }

    /** 条目执行状态：当日 DONE 工单=已完成；未完成工单=已派单；计划日已过未派单=超期；未来=计划。
     *  日期取相对今天动态值，避免随时间推移失效 */
    @Test
    void annotatesExecutionStatus() {
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

        LocalDate anchor = LocalDate.now().minusDays(40);
        WorkOrder done = new WorkOrder();
        done.id = "wo_done";
        done.elevatorId = "el_1";
        done.workTypeCode = "HM";
        done.status = "DONE";
        done.orderNo = "WB2026001";
        done.planTime = anchor.atTime(9, 0);
        WorkOrder open = new WorkOrder();
        open.id = "wo_open";
        open.elevatorId = "el_1";
        open.workTypeCode = "HM";
        open.status = "PENDING";
        open.planTime = anchor.plusDays(15).atTime(9, 0);
        // 同一列表同时服务锚点查询（取首个 planTime 非空）与窗口状态查询
        when(workOrderMapper.selectList(any())).thenReturn(List.of(done, open));
        MaintenanceCalendarService svc = new MaintenanceCalendarService(
                elevatorMapper, employeeMapper, workOrderMapper, unrestrictedScope());

        List<Map<String, Object>> rows = entries(svc, anchor.minusDays(1), anchor.plusDays(50));
        assertEquals("DONE", statusOn(rows, anchor));
        assertEquals("DISPATCHED", statusOn(rows, anchor.plusDays(15)));
        assertEquals("OVERDUE", statusOn(rows, anchor.plusDays(30))); // 过去、无工单
        assertEquals("PLANNED", statusOn(rows, anchor.plusDays(45))); // 未来（今天+5）、无工单
        assertEquals("wo_done", rows.stream()
                .filter(r -> anchor.toString().equals(r.get("date")))
                .map(r -> String.valueOf(r.get("orderId"))).findFirst().orElse(""));
    }

    /** 区域抢单池读谓词（2026-11-21 M9）：非管理角色按区域匹配键——
     *  电梯不限区域或与登录人同区域可见、异区域不可见；电梯绑定字段不作归属键 */
    @Test
    void regionPredicateReplacesWorkerBinding() {
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        Employee emp = new Employee();
        emp.id = "emp_1";
        emp.name = "张伟";
        emp.platformId = "wp_1";
        emp.regionId = "r1";
        when(employeeMapper.selectById("emp_1")).thenReturn(emp);
        EmployeeScopeService scope = mock(EmployeeScopeService.class);
        when(scope.visibleReadPlatformIds(any())).thenReturn(java.util.Set.of("wp_1")); // 非管理 → 区域过滤生效
        Elevator same = new Elevator();
        same.id = "el_r1";
        same.elevatorCode = "EM-R1";
        same.regionId = "r1";
        same.lastMaintenanceAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        Elevator other = new Elevator();
        other.id = "el_r2";
        other.elevatorCode = "EM-R2";
        other.regionId = "r2";
        other.lastMaintenanceAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        Elevator open = new Elevator();
        open.id = "el_r0";
        open.elevatorCode = "EM-R0";
        open.regionId = "";
        open.lastMaintenanceAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(same, other, open));
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        MaintenanceCalendarService svc = new MaintenanceCalendarService(
                elevatorMapper, employeeMapper, workOrderMapper, scope);
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        java.util.Set<String> codes = new java.util.HashSet<>();
        rows.forEach(r -> codes.add(String.valueOf(r.get("elevatorCode"))));
        assertEquals(java.util.Set.of("EM-R1", "EM-R0"), codes);
    }

    /** 提前维保锚点滚动（2026-11-21 用户确认修复）：计划 12-05 的首保提前到 12-01 签退后，
     *  锚点=最近 DONE 签退日 12-01，后续投影整体前移（12-01/12-16/12-31…），
     *  签退日条目按实际作业日标注 DONE */
    @Test
    void anchorRollsWithEarlyCheckout() {
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        Employee emp = new Employee();
        emp.id = "emp_1";
        emp.name = "张伟";
        when(employeeMapper.selectById("emp_1")).thenReturn(emp);
        Elevator el = new Elevator();
        el.id = "el_9";
        el.elevatorCode = "EM-ROLL";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        WorkOrder done = new WorkOrder();
        done.id = "wo_done";
        done.elevatorId = "el_9";
        done.workTypeCode = "HM";
        done.status = "DONE";
        done.planTime = LocalDateTime.of(2026, 12, 5, 9, 0);    // 原计划 12-05
        done.checkoutTime = LocalDateTime.of(2026, 12, 1, 11, 0); // 提前到 12-01 签退
        when(workOrderMapper.selectOne(any())).thenReturn(done);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(done));
        MaintenanceCalendarService svc = new MaintenanceCalendarService(
                elevatorMapper, employeeMapper, workOrderMapper, unrestrictedScope());
        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 11, 25), LocalDate.of(2027, 1, 15));
        // 锚点=12-01（签退日），节奏 12-01/12-16/12-31/01-15，不再是 12-05/12-20…
        assertEquals("2026-12-01", rows.get(0).get("date"));
        assertEquals("DONE", statusOn(rows, LocalDate.of(2026, 12, 1)));
        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 12, 16)));
        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 12, 31)));
        assertEquals("HM", typeOn(rows, LocalDate.of(2027, 1, 15)));
        // 旧冻结锚点会投出的 12-05/12-20 不应出现
        assertTrue(rows.stream().noneMatch(r -> "2026-12-05".equals(r.get("date"))));
        assertTrue(rows.stream().noneMatch(r -> "2026-12-20".equals(r.get("date"))));
    }

    /**
     * 2026-11-21 审查修复（per-type 基线，DispatchService lastOf(type, first) 同口径）：
     * 一年只做半月保的电梯（首保 2025-06-01，最近 HM 签退 2026-05-17=锚点）——
     * 锚点滚动不得把 TM/SM/OY 视为「刚发生」：OY 应落首个距首保 ≥365 天的格点 2026-06-01
     * （旧实现 lastOY/SM/TM 恒从锚点起算 → OY 要等到 2027-05，与派单 2026-06-01 出年度单脱节）。
     * 派单同口径推演：OY(06-01) 后下一单 06-16 时 SM 距首保 380≥180 → SM；07-01 时 TM 395≥90 → TM。
     */
    @Test
    void typeBaselinesFollowPerTypeHistory() {
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        Employee emp = new Employee();
        emp.id = "emp_1";
        emp.name = "张伟";
        when(employeeMapper.selectById("emp_1")).thenReturn(emp);
        Elevator el = new Elevator();
        el.id = "el_oy";
        el.elevatorCode = "EM-OY";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        WorkOrder first = new WorkOrder();
        first.id = "wo_first";
        first.elevatorId = "el_oy";
        first.workTypeCode = "HM";
        first.status = "DONE";
        first.planTime = LocalDateTime.of(2025, 6, 1, 9, 0);
        first.checkoutTime = LocalDateTime.of(2025, 6, 1, 11, 0); // 首保
        WorkOrder last = new WorkOrder();
        last.id = "wo_last";
        last.elevatorId = "el_oy";
        last.workTypeCode = "HM";
        last.status = "DONE";
        last.planTime = LocalDateTime.of(2026, 5, 17, 9, 0);
        last.checkoutTime = LocalDateTime.of(2026, 5, 17, 11, 0); // 最近一次 HM（锚点）
        when(workOrderMapper.selectOne(any())).thenReturn(last);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(first, last));
        MaintenanceCalendarService svc = new MaintenanceCalendarService(
                elevatorMapper, employeeMapper, workOrderMapper, unrestrictedScope());

        List<Map<String, Object>> rows = entries(svc,
                LocalDate.of(2026, 5, 17), LocalDate.of(2026, 8, 31));

        assertEquals("OY", typeOn(rows, LocalDate.of(2026, 6, 1)));  // 距首保 365 天
        assertEquals("SM", typeOn(rows, LocalDate.of(2026, 6, 16))); // OY 后 SM 距首保 380≥180
        assertEquals("TM", typeOn(rows, LocalDate.of(2026, 7, 1)));  // TM 距首保 395≥90
        assertEquals("HM", typeOn(rows, LocalDate.of(2026, 7, 16))); // 各类时钟刷新后回到半月
    }

    private static String statusOn(List<Map<String, Object>> rows, LocalDate date) {
        return rows.stream()
                .filter(r -> date.toString().equals(r.get("date")))
                .map(r -> String.valueOf(r.get("status")))
                .findFirst().orElseThrow(() -> new AssertionError("未投影出 " + date));
    }

    private static String typeOn(List<Map<String, Object>> rows, LocalDate date) {
        return rows.stream()
                .filter(r -> date.toString().equals(r.get("date")))
                .map(r -> String.valueOf(r.get("workTypeCode")))
                .findFirst().orElseThrow(() -> new AssertionError("未投影出 " + date));
    }
}
