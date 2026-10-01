package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.LocationAppeal;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.LocationAppealMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ApprovalService;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 定位异常申述审核（docs/04 A.2/A.6）：提交→审核通过补签到解锁；已处理不可重复审核 */
class ApprovalServiceTest {

    private LocationAppealMapper appealMapper;
    private WorkOrderMapper orderMapper;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        appealMapper = mock(LocationAppealMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        service = new ApprovalService(appealMapper, orderMapper, employeeMapper, elevatorMapper);
    }

    @Test
    void submitInsertsPendingAppeal() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        when(orderMapper.selectById("wo_1")).thenReturn(o);

        Map<String, Object> out = service.submit("wo_1",
                Map.of("reason", "地下机房定位漂移", "distance", 350.8, "threshold", 200), "emp_1");

        ArgumentCaptor<LocationAppeal> captor = ArgumentCaptor.forClass(LocationAppeal.class);
        verify(appealMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().status);
        assertEquals("wo_1", captor.getValue().workOrderId);
        assertEquals("PENDING", out.get("status"));
    }

    @Test
    void auditApproveUnlocksCheckin() {
        LocationAppeal a = new LocationAppeal();
        a.id = "ap_1";
        a.workOrderId = "wo_1";
        a.status = "PENDING";
        when(appealMapper.selectById("ap_1")).thenReturn(a);
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.checkinExtraJson = "{\"foo\":1}";
        when(orderMapper.selectById("wo_1")).thenReturn(o);

        Map<String, Object> out = service.audit("ap_1", true, "现场照片属实", "emp_admin");

        assertEquals("APPROVED", out.get("status"));
        ArgumentCaptor<WorkOrder> captor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(orderMapper).updateById(captor.capture());
        Map<String, Object> extra = JsonUtil.readMap(captor.getValue().checkinExtraJson);
        assertEquals(Boolean.TRUE, extra.get("locationAppealApproved"));
        assertEquals("ap_1", extra.get("appealId"));
        assertEquals(1, extra.get("foo")); // 原字段保留
    }

    @Test
    void auditRejectDoesNotUnlock() {
        LocationAppeal a = new LocationAppeal();
        a.id = "ap_2";
        a.workOrderId = "wo_2";
        a.status = "PENDING";
        when(appealMapper.selectById("ap_2")).thenReturn(a);

        assertEquals("REJECTED", service.audit("ap_2", false, "未达申诉条件", "emp_admin").get("status"));
        verify(orderMapper, org.mockito.Mockito.never()).updateById(any(WorkOrder.class));
    }

    @Test
    void auditAlreadyProcessedRejected() {
        LocationAppeal a = new LocationAppeal();
        a.id = "ap_3";
        a.status = "APPROVED";
        when(appealMapper.selectById("ap_3")).thenReturn(a);
        BizException e = assertThrows(BizException.class,
                () -> service.audit("ap_3", true, "", "emp_admin"));
        assertEquals(422, e.getCode());
    }
}
