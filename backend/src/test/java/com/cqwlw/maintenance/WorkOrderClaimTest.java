package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 区域抢单池「签到即认领」（2026-11-20 设计 §4.2 / M1/D4）：
 * ①池工单（未认领）首个 PRINCIPAL 签到经原子条件更新认领，worker* 指向登录人，工单转 PROCESSING；
 * ②并发认领落败（claimOrder 命中 0 行）→ 1003 并回显现认领人姓名，无写副作用；
 * ③登录人无平台ID → 422「请先在管理端完成平台人员登记」，零写副作用（不落库、不认领）；
 * ④已认领工单由他人签到 → requireOrderScope 1403 拦截（池谓词只放行未认领工单）。
 */
class WorkOrderClaimTest {

    private static final String ORDER = "wo_claim";
    private static final String ELEVATOR = "el_claim";

    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private WorkOrderService service;
    private WorkOrder order;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class), mock(PlatformReportService.class),
                new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                mock(FileStorageService.class), TxTestSupport.noopTx(), props);

        order = new WorkOrder();
        order.id = ORDER;
        order.elevatorId = ELEVATOR;
        order.status = "PENDING";
        order.regionId = "rg_yubei";

        // 无坐标电梯：围栏 UNKNOWN 只留痕不拦截，测试聚焦认领逻辑本身
        Elevator elevator = new Elevator();
        elevator.id = ELEVATOR;
        when(elevatorMapper.selectById(ELEVATOR)).thenReturn(elevator);
    }

    private Employee worker(String id, String name, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = "WORKER";
        e.platformId = platformId;
        e.regionId = "rg_yubei";
        when(employeeMapper.selectById(id)).thenReturn(e);
        return e;
    }

    private static Map<String, Object> body() {
        Map<String, Object> b = new HashMap<>();
        b.put("role", "PRINCIPAL");
        b.put("latitude", "29.6");
        b.put("longitude", "106.5");
        return b;
    }

    @Test
    void firstPrincipalCheckinClaimsOrder() {
        worker("emp_zhang", "张三", "pid_zhang");
        when(orderMapper.selectById(ORDER)).thenReturn(order);
        when(orderMapper.claimOrder(ORDER, "张三", "pid_zhang")).thenReturn(1);

        Map<String, Object> r = service.checkin(ORDER, body(), "emp_zhang");

        assertEquals(Boolean.TRUE, r.get("passed"));
        assertEquals("张三", order.workerName, "签到即认领：workerName 应指向首个签到者");
        assertEquals("pid_zhang", order.workerPlatformId, "签到即认领：workerPlatformId 应指向首个签到者");
        assertEquals("PROCESSING", order.status, "认领成功后继续原签到流程（status→PROCESSING 既有）");
        verify(orderMapper).claimOrder(ORDER, "张三", "pid_zhang");
    }

    @Test
    void secondClaimLosesWithClaimerName() {
        worker("emp_li", "李四", "pid_li");
        WorkOrder claimed = new WorkOrder();
        claimed.id = ORDER;
        claimed.status = "PROCESSING";
        claimed.workerName = "张三";
        claimed.workerPlatformId = "pid_zhang";
        // 第一次 selectById（findOr404）读到未认领，认领落败后重查读到已被张三认领
        when(orderMapper.selectById(ORDER)).thenReturn(order, claimed);
        when(orderMapper.claimOrder(ORDER, "李四", "pid_li")).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(), "emp_li"));
        assertEquals(1003, e.getCode(), "并发认领落败应 1003");
        assertTrue(e.getMessage().contains("张三"), "落败提示应回显现认领人姓名：" + e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        assertEquals("PENDING", order.status, "落败不得改状态（无副作用）");
    }

    @Test
    void claimWithoutPlatformIdRejected() {
        // ADMIN 走 privileged 谓词可越过归属校验，但无平台ID 仍不得认领（D4 门禁兜底）
        Employee admin = new Employee();
        admin.id = "emp_admin";
        admin.name = "管理员";
        admin.role = "ADMIN";
        when(employeeMapper.selectById("emp_admin")).thenReturn(admin);
        when(orderMapper.selectById(ORDER)).thenReturn(order);

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(), "emp_admin"));
        assertEquals(422, e.getCode(), "无平台ID 不得签到接单");
        assertTrue(e.getMessage().contains("平台人员登记"), "提示应引导到管理端登记：" + e.getMessage());
        verify(orderMapper, never()).claimOrder(anyString(), anyString(), anyString());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        assertEquals("PENDING", order.status, "422 拦截不得有任何写副作用");
    }

    @Test
    void claimedOrderKeepsOriginalOwner() {
        order.workerName = "张三";
        order.workerPlatformId = "pid_zhang";
        worker("emp_li", "李四", "pid_li");
        when(orderMapper.selectById(ORDER)).thenReturn(order);

        BizException e = assertThrows(BizException.class,
                () -> service.checkin(ORDER, body(), "emp_li"));
        assertEquals(1403, e.getCode(), "已认领工单由他人签到应被归属谓词拦截");
        verify(orderMapper, never()).claimOrder(anyString(), anyString(), anyString());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        assertEquals("pid_zhang", order.workerPlatformId, "认领人不得被覆盖");
    }
}
