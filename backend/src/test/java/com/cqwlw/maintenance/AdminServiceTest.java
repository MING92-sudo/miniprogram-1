package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.RegUploadLog;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminService;
import com.cqwlw.maintenance.service.PlatformTokenService;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端聚合口径：看板复用 home 统计口径 + 上报异常计数；
 * 上报日志分页只返回脱敏摘要字段；隐患分布按 problemCodesJson 聚合。
 */
class AdminServiceTest {

    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper recordMapper;
    private RegUploadLogMapper logMapper;
    private ElevatorMapper elevatorMapper;
    private EmployeeMapper employeeMapper;
    private FaultMapper faultMapper;
    private AdminService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        logMapper = mock(RegUploadLogMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        faultMapper = mock(FaultMapper.class);
        PlatformTokenService tokenService = mock(PlatformTokenService.class);
        when(tokenService.configured()).thenReturn(true);
        when(faultMapper.selectCount(any())).thenReturn(1L);
        // 1006 模板计数桩：自定义模板数返回 0
        com.cqwlw.maintenance.mapper.ChecklistTemplateMapper templateMapper =
                mock(com.cqwlw.maintenance.mapper.ChecklistTemplateMapper.class);
        when(templateMapper.selectCount(any())).thenReturn(0L);
        service = new AdminService(orderMapper, recordMapper, logMapper,
                elevatorMapper, employeeMapper, faultMapper, tokenService, templateMapper);
    }

    private WorkOrder order(String status, String planDayOffset, String checkoutDayOffset) {
        WorkOrder o = new WorkOrder();
        o.id = "wo_" + status + planDayOffset;
        o.status = status;
        o.planTime = TimeUtil.now().plusDays(Long.parseLong(planDayOffset));
        if (!"null".equals(checkoutDayOffset)) {
            o.checkoutTime = TimeUtil.now().plusDays(Long.parseLong(checkoutDayOffset));
        }
        return o;
    }

    @Test
    void dashboardAggregatesOrdersReportsAndPlatform() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("PENDING", "0", "null"),      // 今日到期
                order("PROCESSING", "1", "null"),   // 进行中
                order("PENDING", "-1", "null"),     // 超期
                order("DONE", "-2", "0")));         // 今日完成（趋势）
        // selectCount 调用序：FAILED / REPORTED / SUBMITTED / unconfirmed
        when(recordMapper.selectCount(any())).thenReturn(2L, 1L, 1L, 3L);

        Elevator withGeo = new Elevator();
        withGeo.id = "el_1";
        withGeo.elevatorCode = "EM-1";
        withGeo.lng = new java.math.BigDecimal("106.63");
        withGeo.lat = new java.math.BigDecimal("29.71");
        withGeo.platformSyncedAt = TimeUtil.now().minusHours(2);
        Elevator noGeo = new Elevator();
        noGeo.id = "el_2";
        noGeo.elevatorCode = "EM-2";
        when(elevatorMapper.selectList(any())).thenReturn(List.of(withGeo, noGeo));

        Employee synced = new Employee();
        synced.id = "emp_1";
        synced.syncStatus = "SYNCED";
        Employee pending = new Employee();
        pending.id = "emp_2";
        pending.syncStatus = "NOT_SYNCED";
        when(employeeMapper.selectList(any())).thenReturn(List.of(synced, pending));

        Map<String, Object> d = service.dashboard();

        @SuppressWarnings("unchecked")
        Map<String, Object> orders = (Map<String, Object>) d.get("orders");
        assertEquals(1, orders.get("dueToday"));
        assertEquals(1, orders.get("overdue"));
        assertEquals(1, orders.get("inProgress"));
        assertEquals(2, orders.get("elevatorTotal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> reports = (Map<String, Object>) d.get("reports");
        assertEquals(2, reports.get("failed"));
        assertEquals(3, reports.get("unconfirmed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) d.get("platform");
        assertEquals(1, platform.get("elevatorGeoMissing"));
        assertEquals(1, platform.get("employeePendingSync"));
        assertEquals(Boolean.TRUE, platform.get("platformConfigured"));
        assertFalse(String.valueOf(platform.get("lastSyncAt")).isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) d.get("trend");
        assertEquals(7, trend.size());
        assertEquals(1, trend.get(6).get("completed"));
    }

    @Test
    void recordsReturnLightRowsWithHazardCodesAndPagination() {
        MaintainRecord failed = new MaintainRecord();
        failed.id = "ur_1";
        failed.elevatorName = "世纪大厦 1# 客梯";
        failed.elevatorCode = "EM-2024-001";
        failed.workTypeCode = "HM";
        failed.workerName = "张伟";
        failed.reportStatus = "FAILED";
        failed.retryCount = 0;
        failed.problemCodesJson = "[\"S5\"]";
        failed.createdAt = TimeUtil.now();
        MaintainRecord ok = new MaintainRecord();
        ok.id = "ur_2";
        ok.reportStatus = "REPORTED";
        ok.problemCodesJson = "[\"S0\"]";
        ok.createdAt = TimeUtil.now();
        when(recordMapper.selectList(any())).thenReturn(List.of(failed, ok));

        Map<String, Object> page1 = service.records(Map.of("reportStatus", "FAILED", "page", "1", "size", "1"));
        assertEquals(2, page1.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page1.get("list");
        assertEquals(1, list.size());
        assertEquals("FAILED", list.get(0).get("reportStatus"));
        assertEquals(List.of("S5"), list.get(0).get("problemCodes"));
        assertFalse(String.valueOf(list.get(0).get("uploadStatus")).isEmpty());
    }

    @Test
    void uploadLogsPaginateAndExposeDigestOnly() {
        RegUploadLog g = new RegUploadLog();
        g.id = "log_1";
        g.originalRecordId = "1948";
        g.action = "REUPLOAD";
        g.requestDigest = "elevatorCode=212520;recorderPhone=138****0001";
        g.platformCode = "200";
        g.status = "SUCCESS";
        g.createdAt = TimeUtil.now();
        when(logMapper.selectList(any())).thenReturn(List.of(g, g, g));

        Map<String, Object> out = service.uploadLogs(Map.of("page", "1", "size", "2"));
        assertEquals(3, out.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("list");
        assertEquals(2, list.size());
        // 脱敏红线：只暴露摘要字段，不含任何原始报文字段
        for (Map<String, Object> row : list) {
            assertTrue(row.containsKey("requestDigest"));
            for (String banned : List.of("requestBody", "responseBody", "token", "phone")) {
                assertFalse(row.containsKey(banned), "不得出现字段: " + banned);
            }
        }
    }

    @Test
    void statsAggregateHazardDistributionByProblemCodes() {
        WorkOrder done1 = order("DONE", "-3", "-3");
        WorkOrder done2 = order("DONE", "-2", "-2");
        WorkOrder pending = order("PENDING", "0", "null");
        when(orderMapper.selectList(any())).thenReturn(List.of(done1, done2, pending));

        MaintainRecord r1 = new MaintainRecord();
        r1.workerName = "张伟";
        r1.elevatorCode = "EM-1";
        r1.problemCodesJson = JsonUtil.write(List.of("S0"));
        r1.createdAt = TimeUtil.now();
        MaintainRecord r2 = new MaintainRecord();
        r2.workerName = "张伟";
        r2.elevatorCode = "EM-1";
        r2.problemCodesJson = JsonUtil.write(List.of("S5"));
        r2.createdAt = TimeUtil.now();
        MaintainRecord r3 = new MaintainRecord();
        r3.workerName = "李强";
        r3.elevatorCode = "EM-2";
        r3.problemCodesJson = JsonUtil.write(List.of("S5", "S2"));
        r3.createdAt = TimeUtil.now();
        when(recordMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));

        Map<String, Object> s = service.stats("30");

        @SuppressWarnings("unchecked")
        Map<String, Object> orders = (Map<String, Object>) s.get("orders");
        assertEquals(3, orders.get("total"));
        assertEquals(2, orders.get("done"));
        assertEquals(66.7, orders.get("completionRate"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> hazard = (Map<String, Integer>) s.get("hazardDist");
        assertEquals(8, hazard.size());
        assertEquals(1, hazard.get("S0"));
        assertEquals(2, hazard.get("S5"));
        assertEquals(1, hazard.get("S2"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byWorker = (List<Map<String, Object>>) s.get("byWorker");
        assertEquals("张伟", byWorker.get(0).get("name"));
        assertEquals(2, byWorker.get(0).get("count"));
    }
}
