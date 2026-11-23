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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 节点签字授权以权限矩阵三态为准：仅节点能力持有者可签，其余一律 1403 且不落痕。 */
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
                mock(DispatchService.class), mock(com.cqwlw.maintenance.service.HandoverArchiveSupport.class));

        HandoverOrder order = new HandoverOrder();
        order.id = ORDER;
        order.status = "PENDING_CONFIRM";
        order.projectName = "渝北区某项目";
        when(orderMapper.selectById(ORDER)).thenReturn(order);
        when(employeeMapper.selectById("emp_sup")).thenReturn(employee("emp_sup", "王主管", "MAINT_SUP"));
        when(employeeMapper.selectById("emp_mm")).thenReturn(employee("emp_mm", "赵经理", "MAINT_MGR"));
        when(employeeMapper.selectById("emp_leader")).thenReturn(employee("emp_leader", "钱班长", "LEADER"));
        when(employeeMapper.selectById("emp_worker")).thenReturn(employee("emp_worker", "李工", "WORKER"));
        when(employeeMapper.selectById("emp_inst_mgr")).thenReturn(employee("emp_inst_mgr", "孙经理", "INST_MGR"));
        when(employeeMapper.selectById("emp_installer")).thenReturn(employee("emp_installer", "赵安装", "INSTALLER"));
        when(employeeMapper.selectById("emp_platform")).thenReturn(employee("emp_platform", "平台管理员", "PLATFORM_ADMIN"));
        when(employeeMapper.selectById("emp_admin")).thenReturn(employee("emp_admin", "旧管理员", "ADMIN"));
    }

    /** 节点通过 = 签字：actor_id/actor_name 取登录人，落 handover_sign */
    @Test
    void signNodeRecordsAccountName() {
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_sup");

        ArgumentCaptor<HandoverSign> cap = ArgumentCaptor.forClass(HandoverSign.class);
        verify(signMapper).insert(cap.capture());
        HandoverSign sign = cap.getValue();
        assertEquals(ORDER, sign.handoverId);
        assertEquals("MAINTENANCE_CONFIRM", sign.nodeType);
        assertEquals("emp_sup", sign.actorId);
        assertEquals("王主管", sign.actorName, "签字人账号名应自动填写");
        assertEquals("APPROVE", sign.action);
        assertNotNull(sign.at, "签字时间应落库");
    }

    /** 维保部经理确认节点：MAINT_SUP / MAINT_MGR 可签 */
    @Test
    void maintenanceConfirmAllowsSupAndManager() {
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_sup");
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_mm");
        verify(signMapper, times(2)).insert(any(HandoverSign.class));
    }

    /** 维保部经理确认节点：维修工/班长不可代签 */
    @Test
    void maintenanceConfirmDeniesOtherRoles() {
        for (String actor : new String[]{"emp_worker", "emp_leader"}) {
            BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                    Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), actor));
            assertEquals(1403, e.getCode(), actor + " 不应可签维保确认节点");
        }
        verify(signMapper, never()).insert(any(HandoverSign.class));
    }

    /** 平台管理员经矩阵全 Y 兜底持有签字能力（与 PermissionMatrix 一致，非本节点角色白名单）。 */
    @Test
    void platformAdminHoldsSignViaGlobalFallback() {
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_platform");
        service.signNode(ORDER, Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_admin");
        verify(signMapper, times(2)).insert(any(HandoverSign.class));
    }

    /** 安装部移交节点：INST_MGR / INSTALLER 可签，班长不可代签 */
    @Test
    void installerHandoverAllowsInstallManagerAndInstaller() {
        service.signNode(ORDER, Map.of("nodeType", "INSTALLER_HANDOVER", "action", "APPROVE"), "emp_inst_mgr");
        service.signNode(ORDER, Map.of("nodeType", "INSTALLER_HANDOVER", "action", "REJECT"), "emp_installer");
        ArgumentCaptor<HandoverSign> cap = ArgumentCaptor.forClass(HandoverSign.class);
        verify(signMapper, times(2)).insert(cap.capture());
        assertEquals("REJECT", cap.getAllValues().get(1).action);
        assertEquals("赵安装", cap.getAllValues().get(1).actorName);

        BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "INSTALLER_HANDOVER", "action", "APPROVE"), "emp_leader"));
        assertEquals(1403, e.getCode(), "班长不应可签安装部移交节点");
    }

    /** 现场查验节点：MAINT_TECH / INSTALLER 可签，班长不可代签 */
    @Test
    void siteInspectAllowsTechAndInstaller() {
        service.signNode(ORDER, Map.of("nodeType", "SITE_INSPECT", "action", "APPROVE"), "emp_worker");
        service.signNode(ORDER, Map.of("nodeType", "SITE_INSPECT", "action", "APPROVE"), "emp_installer");
        verify(signMapper, times(2)).insert(any(HandoverSign.class));

        BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "SITE_INSPECT", "action", "APPROVE"), "emp_leader"));
        assertEquals(1403, e.getCode(), "班长不应可签现场查验节点");
    }

    /** 维保部经理确认节点：维修工不可签 */
    @Test
    void signNodeRejectsNonAssignedRole() {
        BizException e = assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "APPROVE"), "emp_worker"));
        assertEquals(1403, e.getCode(), "非该节点角色签字应 1403");
        verify(signMapper, never()).insert(any(HandoverSign.class));
    }

    /** 未知节点/非法动作 → 422（客户端契约校验，不得静默写脏数据） */
    @Test
    void unknownNodeOrActionRejected() {
        assertEquals(422, assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "NOT_A_NODE", "action", "APPROVE"), "emp_sup")).getCode());
        assertEquals(422, assertThrows(BizException.class, () -> service.signNode(ORDER,
                Map.of("nodeType", "MAINTENANCE_CONFIRM", "action", "PASS"), "emp_sup")).getCode());
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
