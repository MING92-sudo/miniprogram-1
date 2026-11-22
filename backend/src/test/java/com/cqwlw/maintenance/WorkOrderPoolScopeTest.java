package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 区域抢单池可见性与归属谓词：未认领（workerPlatformId 空）工单——登录人持平台ID 且（工单不限区域 regionId 空，或工单 regionId == 登录人 regionId）即可见/可操作； */
class WorkOrderPoolScopeTest {

    private static final String R1 = "region_yubei";
    private static final String R2 = "region_jiangbei";
    private static final String PID_W1 = "pid_w1";
    private static final String PID_PEER = "pid_peer";
    private static final String PID_W2 = "pid_w2";
    private static final String PID_NOREGION = "pid_noregion";

    private static final String EMP_W1 = "emp_w1";             // 张伟：R1 持平台ID
    private static final String EMP_PEER = "emp_peer";         // 同区域同事：R1 持平台ID
    private static final String EMP_W2 = "emp_w2";             // 李强：R2 持平台ID
    private static final String EMP_NOPID = "emp_nopid";       // 王五：R1 但无平台ID
    private static final String EMP_NOREGION = "emp_noregion"; // 赵六：无区域但持平台ID
    private static final String EMP_ADMIN = "emp_admin";       // 管理角色：全可见

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        WorkOrderMapper orderMapper = mock(WorkOrderMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        service = new WorkOrderService(orderMapper, mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                mock(FileStorageService.class), TxTestSupport.noopTx(), new AppProperties());

        when(employeeMapper.selectById(EMP_W1)).thenReturn(employee(EMP_W1, "WORKER", R1, PID_W1));
        when(employeeMapper.selectById(EMP_PEER)).thenReturn(employee(EMP_PEER, "WORKER", R1, PID_PEER));
        when(employeeMapper.selectById(EMP_W2)).thenReturn(employee(EMP_W2, "WORKER", R2, PID_W2));
        when(employeeMapper.selectById(EMP_NOPID)).thenReturn(employee(EMP_NOPID, "WORKER", R1, null));
        when(employeeMapper.selectById(EMP_NOREGION)).thenReturn(employee(EMP_NOREGION, "WORKER", null, PID_NOREGION));
        when(employeeMapper.selectById(EMP_ADMIN)).thenReturn(employee(EMP_ADMIN, "ADMIN", null, null));
        // 区域成员展开（visibleReadPlatformIds 用）：内容不影响池分支断言，给同区两人即可
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                employee(EMP_W1, "WORKER", R1, PID_W1),
                employee(EMP_PEER, "WORKER", R1, PID_PEER)));
    }

    /** 池象限①：同区域 + 持平台ID → 未认领工单可见且可过归属校验 */
    @Test
    void unclaimedOrderVisibleToSameRegionWorkerWithPlatformId() {
        WorkOrder o = poolOrder("wo_pool_r1", R1);
        assertEquals(1, service.scopeOrders(List.of(o), EMP_W1).size());
        assertDoesNotThrow(() -> service.requireOrderScope(o, EMP_W1));
    }

    /** 池象限②：持平台ID但区域不同 → 不可见、1403 */
    @Test
    void unclaimedOrderInvisibleToOtherRegion() {
        WorkOrder o = poolOrder("wo_pool_r1", R1);
        assertTrue(service.scopeOrders(List.of(o), EMP_W2).isEmpty());
        BizException e = assertThrows(BizException.class, () -> service.requireOrderScope(o, EMP_W2));
        assertEquals(1403, e.getCode());
    }

    @Test
    void unclaimedOrderInvisibleToWorkerWithoutPlatformId() {
        WorkOrder o = poolOrder("wo_pool_r1", R1);
        assertTrue(service.scopeOrders(List.of(o), EMP_NOPID).isEmpty());
        BizException e = assertThrows(BizException.class, () -> service.requireOrderScope(o, EMP_NOPID));
        assertEquals(1403, e.getCode());
    }

    @Test
    void noRegionOrderVisibleToAnyPlatformIdHolder() {
        WorkOrder o = poolOrder("wo_pool_free", null);
        assertEquals(1, service.scopeOrders(List.of(o), EMP_NOREGION).size());
        assertDoesNotThrow(() -> service.requireOrderScope(o, EMP_NOREGION));
        assertEquals(1, service.scopeOrders(List.of(o), EMP_W1).size());
        // 无平台ID 员工即使面对不限区域工单也不可见
        assertTrue(service.scopeOrders(List.of(o), EMP_NOPID).isEmpty());
    }

    /** 认领回收：工单被同区域同事认领后，其他池成员不可操作（1403）；管理角色仍全可见 */
    @Test
    void claimedOrderInvisibleToOtherPoolMembers() {
        WorkOrder claimed = claimedOrder("wo_claimed", R1, PID_W1);
        BizException e = assertThrows(BizException.class, () -> service.requireOrderScope(claimed, EMP_PEER));
        assertEquals(1403, e.getCode());
        // 认领人本人不受影响
        assertDoesNotThrow(() -> service.requireOrderScope(claimed, EMP_W1));
        // 管理角色（ids==null）全可见语义不变
        assertDoesNotThrow(() -> service.requireOrderScope(claimed, EMP_ADMIN));
        assertEquals(1, service.scopeOrders(List.of(claimed), EMP_ADMIN).size());
    }

    private static WorkOrder poolOrder(String id, String regionId) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.workTypeCode = "HM";
        o.status = "PENDING";
        o.workerName = null;
        o.workerPlatformId = null;
        o.assistantName = null;
        o.assistantPlatformId = null;
        o.regionId = regionId;
        return o;
    }

    private static WorkOrder claimedOrder(String id, String regionId, String workerPlatformId) {
        WorkOrder o = poolOrder(id, regionId);
        o.status = "PROCESSING";
        o.workerName = "张伟";
        o.workerPlatformId = workerPlatformId;
        return o;
    }

    private static Employee employee(String id, String role, String regionId, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = id;
        e.role = role;
        e.regionId = regionId;
        e.platformId = platformId;
        e.groupName = "维保一班";
        return e;
    }
}
