package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
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
 * 现场作业端点的工单归属校验（V7 班组数据权限）：签到 / 清单 / 单项提交 / 本次仍执行 / 签退
 * 都须先过 requireOrderScope。缺此校验时任一已登录作业人员凭他人工单 id 即可代签到、代填检查项、
 * 代签退，而签退会自动转发平台 2.6 —— 等于向监管平台写入虚假维保数据（AGENTS §6 合规底线 → Blocker）。
 */
class WorkOrderWriteScopeTest {

    private static final String GROUP = "维保一班";
    private static final String PID_ZHANG = "pid_zhang";   // 张伟：工单主维保（非本用例登录人）
    private static final String PID_LI = "pid_li";         // 李强：本用例登录人（组员）
    /** 他人工单，已签到但清单未填完：无归属校验时会走业务分支（1003/422）而不是 1403 */
    private static final String FOREIGN_ORDER = "wo_foreign";
    /** 他人工单，待签到：组长（同班组）可代作业的正向对照 */
    private static final String GROUP_ORDER = "wo_group";
    /** 本人工单：正向对照，不能被新校验拦掉 */
    private static final String OWN_ORDER = "wo_own";
    private static final String EMP_LI = "emp_li";
    private static final String EMP_CHEN = "emp_chen";
    private static final String CHECKLIST = "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false}]";

    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper recordMapper;
    private PlatformReportService reportService;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        reportService = mock(PlatformReportService.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        service = new WorkOrderService(orderMapper, mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                employeeMapper, mock(CompanyMapper.class), recordMapper, mock(FaultMapper.class),
                mock(InspectRecordMapper.class), mock(ChecklistService.class), mock(DispatchService.class),
                reportService, new EmployeeScopeService(employeeMapper), new AppProperties());

        when(orderMapper.selectById(FOREIGN_ORDER))
                .thenReturn(order(FOREIGN_ORDER, PID_ZHANG, "PROCESSING", TimeUtil.now().minusHours(2)));
        when(orderMapper.selectById(GROUP_ORDER)).thenReturn(order(GROUP_ORDER, PID_ZHANG, "PENDING", null));
        when(orderMapper.selectById(OWN_ORDER)).thenReturn(order(OWN_ORDER, PID_LI, "PENDING", null));
        when(employeeMapper.selectById(EMP_LI)).thenReturn(employee(EMP_LI, "李强", "WORKER", PID_LI));
        when(employeeMapper.selectById(EMP_CHEN)).thenReturn(employee(EMP_CHEN, "陈刚", "LEADER", "pid_chen"));
        // 组长按班组展开：本组 = 张伟 + 李强（EmployeeScopeService.sameGroup 走 selectList）
        when(employeeMapper.selectList(any())).thenReturn(List.of(
                employee("emp_zhang", "张伟", "WORKER", PID_ZHANG),
                employee(EMP_LI, "李强", "WORKER", PID_LI)));
    }

    @Test
    void workerCannotCheckinForeignOrder() {
        assertForbidden(() -> service.checkin(FOREIGN_ORDER, Map.of("role", "PRINCIPAL"), EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void workerCannotReadForeignChecklist() {
        assertForbidden(() -> service.getChecklist(FOREIGN_ORDER, EMP_LI));
    }

    @Test
    void workerCannotSubmitForeignChecklistItem() {
        assertForbidden(() -> service.submitItem(FOREIGN_ORDER, "it_1", Map.of("result", "NORMAL"), EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    @Test
    void workerCannotRunThisTimeOnForeignOrder() {
        assertForbidden(() -> service.runThisTime(FOREIGN_ORDER, "it_1", EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    /** 签退是 2.6 上报入口：越权必须拦在业务校验之前，且不留本地记录、不触发上报 */
    @Test
    void workerCannotCheckoutForeignOrder() {
        assertForbidden(() -> service.checkout(FOREIGN_ORDER, Map.of(), EMP_LI));
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
        verify(reportService, never()).attemptUpload(any());
    }

    @Test
    void workerCanOperateOwnOrder() {
        Map<String, Object> result = service.checkin(OWN_ORDER, Map.of("role", "PRINCIPAL"), EMP_LI);
        assertNotNull(result.get("checkinId"));
        assertNotNull(service.getChecklist(OWN_ORDER, EMP_LI).get("items"));
    }

    /** 组长（同班组）仍可作业组员工单——校验不得把 V7 的组长视角一起锁死 */
    @Test
    void groupLeaderCanOperateGroupMemberOrder() {
        Map<String, Object> result = service.checkin(GROUP_ORDER, Map.of("role", "PRINCIPAL"), EMP_CHEN);
        assertNotNull(result.get("checkinId"));
        assertEquals(1, ((List<?>) service.getChecklist(GROUP_ORDER, EMP_CHEN).get("items")).size());
    }

    private void assertForbidden(Runnable call) {
        BizException e = assertThrows(BizException.class, call::run);
        assertEquals(1403, e.getCode(), "越权作业应返回 1403（仅可查看或操作本人工单或本班组工单）");
    }

    private static WorkOrder order(String id, String workerPlatformId, String status, LocalDateTime checkinTime) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.workTypeCode = "HM";
        o.status = status;
        o.workerName = "张伟";
        o.workerPlatformId = workerPlatformId;
        o.assistantName = "";
        o.assistantPlatformId = "";
        o.checkinTime = checkinTime;
        o.checklistJson = CHECKLIST;
        return o;
    }

    private static Employee employee(String id, String name, String role, String platformId) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.role = role;
        e.platformId = platformId;
        e.groupName = GROUP;
        return e;
    }
}
