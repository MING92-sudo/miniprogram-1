package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.TxTestSupport;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLifecycleServiceTest {

    private static final String ACTOR = "emp_admin";
    private static final String TARGET_ID = "emp_target";
    private static final String HEIR_ID = "emp_heir";
    private static final String TARGET_PID = "pid_target";
    private static final String HEIR_PID = "pid_heir";
    private static final String REGION = "reg_1";

    private EmployeeMapper employeeMapper;
    private WorkOrderMapper orderMapper;
    private FaultMapper faultMapper;
    private WorkOrderService workOrderService;
    private TxTestSupport.CapturingTx tx;
    private UserLifecycleService service;
    private Employee target;
    private Employee heir;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Employee.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Fault.class);

        employeeMapper = mock(EmployeeMapper.class);
        orderMapper = mock(WorkOrderMapper.class);
        faultMapper = mock(FaultMapper.class);
        workOrderService = mock(WorkOrderService.class);
        tx = TxTestSupport.capturingTx();
        service = new UserLifecycleService(employeeMapper, orderMapper, faultMapper, workOrderService, tx.template);

        target = employee(TARGET_ID, "张三", TARGET_PID, REGION);
        heir = employee(HEIR_ID, "李四", HEIR_PID, REGION);
        stubLocks();
        when(orderMapper.reassignWorker(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(orderMapper.reassignAssistant(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(faultMapper.reassignDispatchWorker(anyString(), anyString())).thenReturn(1);
    }

    /** 目标行先锁、接手人行后锁；每次调用前重设顺序桩，避免异常分支提前消费后错位。 */
    private void stubLocks() {
        when(employeeMapper.selectList(any())).thenReturn(List.of(target), List.of(heir));
    }

    @Test
    void resignTransfersInflightOrdersAndRevokesAccount() {
        WorkOrder pendingPrincipal = order("wo_1", "PENDING", TARGET_PID, "pid_other");
        WorkOrder pendingAssistant = order("wo_2", "PENDING", "pid_other", TARGET_PID);
        WorkOrder done = order("wo_3", "DONE", TARGET_PID, "pid_other");
        when(orderMapper.selectInflightForUpdate(TARGET_PID))
                .thenReturn(List.of(pendingPrincipal, pendingAssistant, done));

        Fault open = fault("f_1", "OPEN");
        Fault assigned = fault("f_2", "ASSIGNED");
        Fault refused = fault("f_3", "REFUSED");
        Fault closed = fault("f_4", "CLOSED");
        when(faultMapper.selectUnclosedForUpdate(TARGET_ID))
                .thenReturn(List.of(open, assigned, refused, closed));

        Map<String, Object> result = service.resign(ACTOR, TARGET_ID, HEIR_ID);

        verify(orderMapper).reassignWorker("wo_1", "李四", HEIR_PID, TARGET_PID);
        verify(orderMapper).reassignAssistant("wo_2", "李四", HEIR_PID, TARGET_PID);
        verify(orderMapper, never()).reassignWorker(eq("wo_3"), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(eq("wo_3"), anyString(), anyString(), anyString());
        verify(faultMapper, times(3)).reassignDispatchWorker(anyString(), eq(HEIR_ID));
        verify(faultMapper, never()).reassignDispatchWorker(eq("f_4"), anyString());
        verify(employeeMapper).updateById(target);
        assertFalse(target.enabled, "离职后目标账号必须停用");
        verify(employeeMapper).bumpScopeVersion(TARGET_ID);
        assertTrue(tx.committed, "离职转交必须提交事务");

        assertEquals(2, result.get("orders"), "DONE 历史工单不得计入迁移数量");
        assertEquals(3, result.get("faults"), "已闭环急修单不得计入迁移数量");
        assertEquals(5, result.get("total"));
    }

    @Test
    void invalidHeirLeavesNoPartialChanges() {
        // 本人
        stubLocks();
        BizException self = assertThrows(BizException.class,
                () -> service.resign(ACTOR, TARGET_ID, TARGET_ID));
        assertEquals(422, self.getCode());

        // 接手人已停用
        heir.enabled = false;
        stubLocks();
        BizException disabled = assertThrows(BizException.class,
                () -> service.resign(ACTOR, TARGET_ID, HEIR_ID));
        assertEquals(422, disabled.getCode());
        heir.enabled = true;

        // 接手人无平台人员 ID
        heir.platformId = " ";
        stubLocks();
        BizException noPlatform = assertThrows(BizException.class,
                () -> service.resign(ACTOR, TARGET_ID, HEIR_ID));
        assertEquals(422, noPlatform.getCode());
        heir.platformId = HEIR_PID;

        // 区域不匹配
        heir.regionId = "reg_other";
        stubLocks();
        BizException region = assertThrows(BizException.class,
                () -> service.resign(ACTOR, TARGET_ID, HEIR_ID));
        assertEquals(422, region.getCode());

        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).bumpScopeVersion(anyString());
        verify(orderMapper, never()).selectInflightForUpdate(anyString());
        verify(orderMapper, never()).reassignWorker(anyString(), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        verify(faultMapper, never()).reassignDispatchWorker(anyString(), anyString());
    }

    @Test
    void processingOrderNeverCarriesResigningUserEvidence() {
        // 有区域快照：复用退回池重置流程，绝不改人继承旧签到/签名
        WorkOrder processing = order("wo_proc", "PROCESSING", TARGET_PID, "");
        processing.regionId = REGION;
        processing.checkinExtraJson = "{\"geoStatus\":\"PROVIDED\",\"checkedAt\":\"2026-11-20 10:00:00\"}";
        processing.workerSignatureFileId = "file_old_signature";
        when(orderMapper.selectInflightForUpdate(TARGET_PID)).thenReturn(List.of(processing));
        when(workOrderService.releaseToPool("wo_proc", ACTOR)).thenReturn(Map.of("ok", true));

        Map<String, Object> result = service.resign(ACTOR, TARGET_ID, HEIR_ID);

        verify(workOrderService).releaseToPool("wo_proc", ACTOR);
        verify(orderMapper, never()).reassignWorker(anyString(), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        assertEquals(1, result.get("orders"));
        assertTrue(tx.committed);

        // 无区域快照：422 且零副作用、事务回滚
        clearInvocations(employeeMapper, orderMapper, faultMapper, workOrderService);
        WorkOrder noRegion = order("wo_proc_nr", "PROCESSING", TARGET_PID, "");
        noRegion.regionId = null;
        when(orderMapper.selectInflightForUpdate(TARGET_PID)).thenReturn(List.of(noRegion));
        stubLocks();
        TxTestSupport.CapturingTx rollbackTx = TxTestSupport.capturingTx();
        UserLifecycleService rollbackService = new UserLifecycleService(
                employeeMapper, orderMapper, faultMapper, workOrderService, rollbackTx.template);

        BizException e = assertThrows(BizException.class,
                () -> rollbackService.resign(ACTOR, TARGET_ID, HEIR_ID));

        assertEquals(422, e.getCode());
        verify(workOrderService, never()).releaseToPool(anyString(), anyString());
        verify(orderMapper, never()).reassignWorker(anyString(), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).bumpScopeVersion(anyString());
        assertTrue(rollbackTx.rolledBack, "无区域快照的在途工单必须回滚整个离职转交");
    }

    @Test
    void heirAlreadyInOppositeSlotReleasesOrderToPool() {
        WorkOrder principalCollision = order("wo_collision_p", "PENDING", TARGET_PID, HEIR_PID);
        WorkOrder assistantCollision = order("wo_collision_a", "PENDING", HEIR_PID, TARGET_PID);
        when(orderMapper.selectInflightForUpdate(TARGET_PID))
                .thenReturn(List.of(principalCollision, assistantCollision));
        when(workOrderService.releaseToPool(anyString(), anyString())).thenReturn(Map.of("ok", true));

        Map<String, Object> result = service.resign(ACTOR, TARGET_ID, HEIR_ID);

        verify(workOrderService).releaseToPool("wo_collision_p", ACTOR);
        verify(workOrderService).releaseToPool("wo_collision_a", ACTOR);
        verify(orderMapper, never()).reassignWorker(anyString(), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        assertEquals(2, result.get("orders"), "主/配合人相撞的工单各计一次退回池");
        assertTrue(tx.committed);
    }

    @Test
    void oppositeSlotCollisionWithoutRegionRejectsAndRollsBack() {
        WorkOrder collision = order("wo_collision_nr", "PENDING", TARGET_PID, HEIR_PID);
        collision.regionId = null;
        when(orderMapper.selectInflightForUpdate(TARGET_PID)).thenReturn(List.of(collision));
        stubLocks();
        TxTestSupport.CapturingTx rollbackTx = TxTestSupport.capturingTx();
        UserLifecycleService rollbackService = new UserLifecycleService(
                employeeMapper, orderMapper, faultMapper, workOrderService, rollbackTx.template);

        BizException e = assertThrows(BizException.class,
                () -> rollbackService.resign(ACTOR, TARGET_ID, HEIR_ID));

        assertEquals(422, e.getCode());
        verify(workOrderService, never()).releaseToPool(anyString(), anyString());
        verify(orderMapper, never()).reassignWorker(anyString(), anyString(), anyString(), anyString());
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).bumpScopeVersion(anyString());
        assertTrue(rollbackTx.rolledBack, "无区域快照且接手人已占另一角色时必须整体回滚");
    }

    @Test
    void oneSlotReassignmentWorksWhenHeirNotOnOrder() {
        WorkOrder pending = order("wo_normal", "PENDING", TARGET_PID, "pid_other");
        when(orderMapper.selectInflightForUpdate(TARGET_PID)).thenReturn(List.of(pending));

        Map<String, Object> result = service.resign(ACTOR, TARGET_ID, HEIR_ID);

        verify(orderMapper).reassignWorker("wo_normal", "李四", HEIR_PID, TARGET_PID);
        verify(orderMapper, never()).reassignAssistant(anyString(), anyString(), anyString(), anyString());
        verify(workOrderService, never()).releaseToPool(anyString(), anyString());
        assertEquals(1, result.get("orders"));
    }

    private static Employee employee(String id, String name, String platformId, String regionId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.platformId = platformId;
        e.regionId = regionId;
        e.enabled = true;
        e.role = "WORKER";
        return e;
    }

    private static WorkOrder order(String id, String status, String workerPid, String assistantPid) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.status = status;
        o.regionId = REGION;
        o.workerName = workerPid == null ? "" : "原主维保";
        o.workerPlatformId = workerPid;
        o.assistantName = assistantPid == null ? "" : "原配合人";
        o.assistantPlatformId = assistantPid;
        return o;
    }

    private static Fault fault(String id, String status) {
        Fault f = new Fault();
        f.id = id;
        f.faultNo = "BWJX-" + id;
        f.status = status;
        f.dispatchWorkerId = TARGET_ID;
        return f;
    }
}
