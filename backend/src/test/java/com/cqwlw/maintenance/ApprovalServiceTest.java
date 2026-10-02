package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.LocationAppeal;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.LocationAppealMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ApprovalService;
import com.cqwlw.maintenance.util.JsonUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 定位异常申述审核：提交人须为本单作业人、放行只认本人申诉；已处理不可重复审核 */
class ApprovalServiceTest {

    private LocationAppealMapper appealMapper;
    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private ApprovalService service;

    @BeforeAll
static void initMybatisPlusLambdaCache() {
    // 纯单测没有 Spring，MyBatis-Plus 的实体缓存未初始化，渲染 lambda 条件时会抛
    // "can not find lambda cache"。这里手工建一次，才能断言真实列名。
    TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), LocationAppeal.class);
}

@BeforeEach
    void setUp() {
        appealMapper = mock(LocationAppealMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        service = new ApprovalService(appealMapper, orderMapper, employeeMapper, elevatorMapper);
    }

    private WorkOrder assignedOrder() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.workerName = "张伟";
        o.workerPlatformId = "P_PRINCIPAL";
        o.assistantName = "李强";
        o.assistantPlatformId = "P_ASSISTANT";
        return o;
    }

    private void employee(String id, String name, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.platformId = platformId;
        when(employeeMapper.selectById(id)).thenReturn(e);
    }

    @Test
    void submitInsertsPendingAppeal() {
        when(orderMapper.selectById("wo_1")).thenReturn(assignedOrder());
        employee("emp_1", "张伟", "P_PRINCIPAL");

        Map<String, Object> out = service.submit("wo_1",
                Map.of("reason", "地下机房定位漂移", "distance", 350.8, "threshold", 200), "emp_1");

        ArgumentCaptor<LocationAppeal> captor = ArgumentCaptor.forClass(LocationAppeal.class);
        verify(appealMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().status);
        assertEquals("wo_1", captor.getValue().workOrderId);
        assertEquals("emp_1", captor.getValue().employeeId);
        assertEquals("PENDING", out.get("status"));
    }

    @Test
    void submitRejectsWorkerNotAssignedToOrder() {
        when(orderMapper.selectById("wo_1")).thenReturn(assignedOrder());
        employee("emp_9", "王五", "P_OTHER");

        BizException e = assertThrows(BizException.class, () -> service.submit("wo_1",
                Map.of("reason", "路过", "distance", 900, "threshold", 200), "emp_9"));

        assertEquals(403, e.getCode());
        verify(appealMapper, never()).insert(any(LocationAppeal.class));
    }

    @Test
    void submitRejectsSameNameWithDifferentPlatformId() {
        when(orderMapper.selectById("wo_1")).thenReturn(assignedOrder());
        employee("emp_9", "张伟", "P_IMPOSTOR");

        BizException e = assertThrows(BizException.class, () -> service.submit("wo_1",
                Map.of("reason", "同名", "distance", 900, "threshold", 200), "emp_9"));

        assertEquals(403, e.getCode());
        verify(appealMapper, never()).insert(any(LocationAppeal.class));
    }

    @Test
    void submitRejectsMissingEmployee() {
        when(orderMapper.selectById("wo_1")).thenReturn(assignedOrder());
        when(employeeMapper.selectById("ghost")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.submit("wo_1",
                Map.of("reason", "无账号", "distance", 900, "threshold", 200), "ghost"));

        assertEquals(401, e.getCode());
        verify(appealMapper, never()).insert(any(LocationAppeal.class));
    }

    @Test
    void hasApprovedIsScopedToTheApplicant() {
        when(appealMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.hasApproved("wo_1", "emp_1"));

        ArgumentCaptor<LambdaQueryWrapper<LocationAppeal>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(appealMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("work_order_id"), sql);
        assertTrue(sql.contains("employee_id"), sql);
        assertTrue(sql.contains("status"), sql);
    }

    @Test
    void hasApprovedFailsClosedOnBlankInput() {
        assertEquals(false, service.hasApproved("wo_1", null));
        assertEquals(false, service.hasApproved(null, "emp_1"));
        verify(appealMapper, never()).selectCount(any());
    }

    @Test
    void auditApproveUnlocksCheckin() {
        LocationAppeal a = new LocationAppeal();
        a.id = "ap_1";
        a.workOrderId = "wo_1";
        a.employeeId = "emp_1";
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
        assertEquals("emp_1", extra.get("locationAppealApprovedFor"));
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
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
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