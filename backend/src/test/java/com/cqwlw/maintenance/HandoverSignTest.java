package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 移交节点账号签字（Task 6，设计文档 §7）：内部账号「节点通过即签字」，账号名自动填写；
 * 非该节点授权角色签字一律 1403 且不落痕（防伪造他人节点确认）。
 */
class HandoverSignTest {

    private static final String ORDER = "ho_1";

    private HandoverSignMapper signMapper;
    private EmployeeMapper employeeMapper;
    private HandoverService service;

    @BeforeEach
    void setUp() {
        HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
        signMapper = mock(HandoverSignMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        service = new HandoverService(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                signMapper, mock(ElevatorMapper.class), employeeMapper, mock(WorkOrderMapper.class),
                mock(DispatchService.class));

        HandoverOrder order = new HandoverOrder();
        order.id = ORDER;
        order.status = "PENDING_CONFIRM";
        order.projectName = "渝北区某项目";
        when(orderMapper.selectById(ORDER)).thenReturn(order);
        when(employeeMapper.selectById("emp_mgr")).thenReturn(employee("emp_mgr", "王经理", "LEADER"));
        when(employeeMapper.selectById("emp_worker")).thenReturn(employee("emp_worker", "李工", "WORKER"));
        when(employeeMapper.selectById("emp_installer")).thenReturn(employee("emp_installer", "赵安装", "INSTALLER"));
    }

    /** 节点通过 = 签字：actor_id/actor_name 取登录人，落 handover_sign */
    @Test
    void signNodeRecordsAccountName() {
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_mgr");

        ArgumentCaptor<HandoverSign> cap = ArgumentCaptor.forClass(HandoverSign.class);
        verify(signMapper).insert(cap.capture());
        HandoverSign sign = cap.getValue();
        assertEquals(ORDER, sign.handoverId);
        assertEquals("MAINTENANCE_CONFIRM", sign.nodeType);
        assertEquals("emp_mgr", sign.actorId);
        assertEquals("王经理", sign.actorName, "签字人账号名应自动填写");
        assertEquals("APPROVE", sign.action);
        assertNotNull(sign.at, "签字时间应落库");
    }

    /** 维保人员签「维保部经理确认」节点 → 1403，且不写签字记录 */
    @Test
    void signNodeRejectsNonAssignedRole() {
        BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_worker"));
        assertEquals(1403, e.getCode(), "非该节点角色签字应 1403");
        verify(signMapper, never()).insert(any(HandoverSign.class));
    }

    /** 安装部节点仅安装部角色可签（维保部经理同样不可代签） */
    @Test
    void installerNodeRejectsMaintenanceRole() {
        BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "INSTALLER_HANDOVER", "action", "APPROVE"), "emp_mgr"));
        assertEquals(1403, e.getCode());
        verify(signMapper, never()).insert(any(HandoverSign.class));

        service.signNode(ORDER, Map.of("nodeType", "INSTALLER_HANDOVER", "action", "REJECT"), "emp_installer");
        ArgumentCaptor<HandoverSign> cap = ArgumentCaptor.forClass(HandoverSign.class);
        verify(signMapper).insert(cap.capture());
        assertEquals("REJECT", cap.getValue().action);
        assertEquals("赵安装", cap.getValue().actorName);
    }

    /** 未知节点/非法动作 → 422（客户端契约校验，不得静默写脏数据） */
    @Test
    void unknownNodeOrActionRejected() {
        assertEquals(422, assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "NOT_A_NODE", "action", "APPROVE"), "emp_mgr")).getCode());
        assertEquals(422, assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "PASS"), "emp_mgr")).getCode());
        verify(signMapper, never()).insert(any(HandoverSign.class));
    }

    private static Employee employee(String id, String name, String role) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = role;
        e.platformId = "pid_" + id;
        return e;
    }
}
