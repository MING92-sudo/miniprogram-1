package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.CurrentUser;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ApprovalService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EvidenceTokenService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单归属（B8）：维保人员只能查看/操作**本人被指派**的工单。
 * 原实现 listOrders 对工单表全表查询、findOr404 不校验归属，导致甲可代乙作业，
 * 而 2.6 上报记录的是工单上的 workerName（乙）——实际作业人与记录人不一致，属记录造假。
 */
class WorkOrderOwnershipTest {

    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private CurrentUser currentUser;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        currentUser = mock(CurrentUser.class);
        service = new WorkOrderService(
                orderMapper,
                mock(ElevatorMapper.class),
                mock(UseUnitMapper.class),
                employeeMapper,
                mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class),
                mock(FaultMapper.class),
                mock(InspectRecordMapper.class),
                mock(ChecklistService.class),
                mock(ApprovalService.class),
                mock(EvidenceTokenService.class),
                mock(FileStorageService.class),
                mock(PlatformReportService.class),
                new AppProperties(),
                currentUser);
    }

    /** 登录人：角色 + 平台人员标识（2.5 同步写入） */
    private void loginAs(String role, String employeeId, String name, String platformId) {
        Employee me = new Employee();
        me.id = employeeId;
        me.name = name;
        me.platformId = platformId;
        me.role = role;
        when(currentUser.roleOrNull()).thenReturn(role);
        when(currentUser.requireEmployeeId()).thenReturn(employeeId);
        when(employeeMapper.selectById(employeeId)).thenReturn(me);
    }

    private WorkOrder order(String id, String workerName, String workerPlatformId,
                            String assistantName, String assistantPlatformId) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.status = "PENDING";
        o.workerName = workerName;
        o.workerPlatformId = workerPlatformId;
        o.assistantName = assistantName;
        o.assistantPlatformId = assistantPlatformId;
        return o;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listedIds(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("list");
    }

    @Test
    void workerSeesOnlyOwnOrders() {
        loginAs("WORKER", "e_me", "张伟", "P_ME");
        WorkOrder mine = order("wo_mine", "张伟", "P_ME", null, null);
        WorkOrder asAssistant = order("wo_assist", "李强", "P_OTHER", "张伟", "P_ME");
        WorkOrder others = order("wo_other", "李强", "P_OTHER", null, null);
        when(orderMapper.selectList(any())).thenReturn(List.of(mine, asAssistant, others));

        List<Map<String, Object>> list = listedIds(service.listOrders(Map.of()));

        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(m -> "wo_mine".equals(m.get("id"))));
        // 2.6 要求 workMan1Id + workMan2Id 双人，第二人同样有权处理该工单
        assertTrue(list.stream().anyMatch(m -> "wo_assist".equals(m.get("id"))));
    }

    @Test
    void workerCannotOpenOthersOrder() {
        loginAs("WORKER", "e_me", "张伟", "P_ME");
        when(orderMapper.selectById("wo_other"))
                .thenReturn(order("wo_other", "李强", "P_OTHER", null, null));

        BizException e = assertThrows(BizException.class, () -> service.findOr404("wo_other"));
        assertEquals(403, e.getCode());
    }

    @Test
    void workerCanOpenOwnOrder() {
        loginAs("WORKER", "e_me", "张伟", "P_ME");
        WorkOrder mine = order("wo_mine", "张伟", "P_ME", null, null);
        when(orderMapper.selectById("wo_mine")).thenReturn(mine);

        assertSame(mine, service.findOr404("wo_mine"));
    }

    @Test
    void managementRoleSeesAllOrders() {
        loginAs("LEADER", "e_leader", "王经理", "P_LEADER");
        WorkOrder others = order("wo_other", "李强", "P_OTHER", null, null);
        when(orderMapper.selectList(any())).thenReturn(List.of(others));

        assertEquals(1, listedIds(service.listOrders(Map.of())).size());
    }

    @Test
    void sameNameDoesNotGrantAccessWhenPlatformIdsDiffer() {
        // 双方平台标识都在时必须以 platform_id 为准：同名不等于同一人
        loginAs("WORKER", "e_me", "李强", "P_ME");
        when(orderMapper.selectById("wo_other"))
                .thenReturn(order("wo_other", "李强", "P_OTHER", null, null));

        assertEquals(403, assertThrows(BizException.class,
                () -> service.findOr404("wo_other")).getCode());
    }

    @Test
    void fallsBackToNameBeforePlatformSync() {
        // 尚未 2.5 同步（双方 platform_id 均缺失）时按姓名兜底，保证功能可用
        loginAs("WORKER", "e_me", "张伟", null);
        WorkOrder mine = order("wo_mine", "张伟", null, null, null);
        WorkOrder others = order("wo_other", "李强", null, null, null);
        when(orderMapper.selectList(any())).thenReturn(List.of(mine, others));

        List<Map<String, Object>> list = listedIds(service.listOrders(Map.of()));

        assertEquals(1, list.size());
        assertEquals("wo_mine", list.get(0).get("id"));
    }

    @Test
    void missingEmployeeRowFailsClosed() {
        loginAs("WORKER", "e_gone", "张伟", "P_ME");
        when(employeeMapper.selectById("e_gone")).thenReturn(null);
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertEquals(401, assertThrows(BizException.class,
                () -> service.listOrders(Map.of())).getCode());
    }

    @Test
    void missingLoginContextFailsClosed() {
        when(currentUser.roleOrNull()).thenReturn("WORKER");
        when(currentUser.requireEmployeeId())
                .thenThrow(new BizException(401, "登录已过期，请重新登录"));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertEquals(401, assertThrows(BizException.class,
                () -> service.listOrders(Map.of())).getCode());
    }
}