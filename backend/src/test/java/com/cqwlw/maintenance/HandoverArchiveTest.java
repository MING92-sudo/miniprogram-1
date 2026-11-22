package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.HandoverArchiveSupport;
import com.cqwlw.maintenance.service.HandoverService;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.service.TenantSnapshotService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoverArchiveTest {

    private HandoverOrderMapper handoverMapper;
    private ElevatorMapper elevatorMapper;
    private EmployeeMapper employeeMapper;
    private WorkOrderMapper workOrderMapper;
    private MessageMapper messageMapper;
    private DispatchService dispatch;
    private HandoverArchiveSupport archiveSupport;
    private HandoverService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯 Mockito 单测无 MyBatis 环境，insert/updateById 需 TableInfo 缓存，手动初始化一次
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, HandoverOrder.class);
        TableInfoHelper.initTableInfo(assistant, Elevator.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrder.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        handoverMapper = mock(HandoverOrderMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        workOrderMapper = mock(WorkOrderMapper.class);
        messageMapper = mock(MessageMapper.class);
        ChecklistService checklistService = new ChecklistService();
        checklistService.load();
        dispatch = new DispatchService(elevatorMapper, workOrderMapper, messageMapper,
                employeeMapper, checklistService, new OrderNoIssuer(workOrderMapper), mock(TenantSnapshotService.class));
        archiveSupport = mock(HandoverArchiveSupport.class);
        service = new HandoverService(handoverMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class), elevatorMapper, employeeMapper, workOrderMapper, dispatch, archiveSupport);
    }

    private static HandoverOrder handover(String id, boolean withReceiver) {
        HandoverOrder o = new HandoverOrder();
        o.id = id;
        o.projectName = "渝北区某项目";
        o.elevatorCode = "EM-001";
        o.elevatorName = "1号楼客梯";
        o.category = "曳引与强制驱动电梯";
        o.regCode = "REG-001";
        o.deviceCode = "DEV-001";
        o.insideNumber = "KT-01";
        o.factoryNumber = "SGL-001";
        o.useUnitId = "uu_1";
        o.specialType = "消防";
        o.workTypeCode = "HM";
        o.supervisionPassDate = LocalDate.of(2026, 11, 12);
        if (withReceiver) {
            o.receiverId = "emp_r";
            o.receiverName = "李工";
        }
        return o;
    }

    private static Employee receiver() {
        Employee e = new Employee();
        e.id = "emp_r";
        e.name = "李工";
        e.platformId = "pid_li";
        return e;
    }

    @Test
    void archiveCreatesElevatorAndFirstOrder() {
        HandoverOrder order = handover("ho_1", true);
        when(handoverMapper.selectById("ho_1")).thenReturn(order);
        when(employeeMapper.selectById("emp_r")).thenReturn(receiver());

        service.archive("ho_1");

        ArgumentCaptor<Elevator> elCap = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(elCap.capture());
        Elevator el = elCap.getValue();
        assertEquals(Integer.valueOf(1), el.archiveSource, "归档建梯 archiveSource 应为 1（移交）");
        assertNull(el.workerName, "归档建梯不写主维保绑定（区域抢单池 D2/M6）");
        assertNull(el.workerPlatformId, "归档建梯不写主维保平台ID（区域抢单池 D2/M6）");
        assertEquals("EM-001", el.elevatorCode);
        assertEquals("1号楼客梯", el.elevatorName);
        assertEquals("曳引与强制驱动电梯", el.category);
        assertEquals("HM", el.workTypeCode);
        assertEquals("渝北区某项目", el.projectName);
        assertEquals(LocalDate.of(2026, 11, 12), el.supervisionPassDate, "监督检验合格日应复制快照");
        assertNotNull(el.handoverAt, "归档时间应落库");

        ArgumentCaptor<WorkOrder> woCap = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).updateById(woCap.capture());
        WorkOrder wo = woCap.getValue();
        assertEquals("HM", wo.workTypeCode, "首保工单应为半月保");
        assertEquals("PENDING", wo.status);
        assertEquals("ho_1", wo.sourceHandoverId, "首保工单应回指来源移交单");
        assertEquals(Boolean.FALSE, wo.autoDispatched, "首保预建为非自动派单");
        assertNull(wo.workerName, "首保预建工单不得写主维保");
        assertNull(wo.workerPlatformId, "首保预建工单不得写主维保平台ID");
        assertEquals("09:00:00", TimeUtil.format(wo.planTime).substring(11), "首保计划时间应为当日 09:00");

        assertEquals(el.id, order.elevatorId, "移交单应回填电梯 id");
        verify(handoverMapper).updateById(order);
        verify(messageMapper, times(1)).insert(any(Message.class));
    }

    @Test
    void archiveEnrichesCodesBeforeInsertAndCopiesArchiveFields() {
        HandoverOrder order = handover("ho_1", true);
        order.elevatorCode = null;
        order.regCode = null;
        order.deviceCode = null;
        order.supervisionReportFileId = "file_supervision";
        when(handoverMapper.selectById("ho_1")).thenReturn(order);
        when(employeeMapper.selectById("emp_r")).thenReturn(receiver());
        org.mockito.Mockito.doAnswer(inv -> {
            order.elevatorCode = "130421";
            order.regCode = "REG-2026-001";
            order.deviceCode = "DEV-2026-001";
            Elevator el = inv.getArgument(1);
            el.elevatorCode = order.elevatorCode;
            el.regCode = order.regCode;
            el.deviceCode = order.deviceCode;
            return null;
        }).when(archiveSupport).enrich(org.mockito.ArgumentMatchers.eq(order),
                org.mockito.ArgumentMatchers.any(Elevator.class));

        service.archive("ho_1");

        ArgumentCaptor<Elevator> elCap = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).insert(elCap.capture());
        Elevator el = elCap.getValue();
        assertEquals("130421", el.elevatorCode);
        assertEquals("REG-2026-001", el.regCode);
        assertEquals("DEV-2026-001", el.deviceCode);
        assertEquals("uu_1", el.useUnitId);
        assertEquals("消防", el.specialType);
        assertEquals("file_supervision", el.inspectionReportFileId);
        assertEquals("130421", order.elevatorCode);
        assertEquals("REG-2026-001", order.regCode);
        assertEquals("DEV-2026-001", order.deviceCode);
        verify(archiveSupport).enrich(org.mockito.ArgumentMatchers.eq(order),
                org.mockito.ArgumentMatchers.any(Elevator.class));
    }

    @Test
    void archiveRejectsMissingInsideNumberBeforeInsert() {
        HandoverOrder order = handover("ho_1", true);
        order.insideNumber = null;
        when(handoverMapper.selectById("ho_1")).thenReturn(order);
        when(employeeMapper.selectById("emp_r")).thenReturn(receiver());

        org.junit.jupiter.api.Assertions.assertThrows(com.cqwlw.maintenance.common.BizException.class,
                () -> service.archive("ho_1"));

        verify(elevatorMapper, org.mockito.Mockito.never()).insert(any(Elevator.class));
        verify(workOrderMapper, org.mockito.Mockito.never()).insert(any(WorkOrder.class));
    }

    /** 重复归档不重复建电梯/首保工单（幂等由 elevatorId 守卫保证） */
    @Test
    void archiveIsIdempotent() {
        HandoverOrder order = handover("ho_1", true);
        when(handoverMapper.selectById("ho_1")).thenReturn(order);
        when(employeeMapper.selectById("emp_r")).thenReturn(receiver());

        service.archive("ho_1");
        service.archive("ho_1");

        verify(elevatorMapper, times(1)).insert(any(Elevator.class));
        verify(workOrderMapper, times(1)).insert(any(WorkOrder.class));
        verify(workOrderMapper, times(1)).updateById(any(WorkOrder.class));
        verify(messageMapper, times(1)).insert(any(Message.class));
    }

    /** 归档预建首保 PENDING 工单后，定时派单扫描不再给该电梯派新单 */
    @Test
    void archivePrecreatedOrderBlocksScheduledDispatch() {
        HandoverOrder order = handover("ho_1", true);
        when(handoverMapper.selectById("ho_1")).thenReturn(order);
        when(employeeMapper.selectById("emp_r")).thenReturn(receiver());

        service.archive("ho_1"); // 预建 PENDING HM 首保工单

        Elevator el = new Elevator();
        el.id = "el_archived";
        el.elevatorName = "1号楼客梯";
        el.workTypeCode = "HM";
        el.workerName = "李工";
        el.workerPlatformId = "pid_li";
        el.lastMaintenanceAt = TimeUtil.now().minusDays(16);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(el));
        when(workOrderMapper.selectCount(any())).thenReturn(1L); // 预建首保仍在进行中

        assertEquals(0, dispatch.ensureDueOrders().size(), "已有进行中的预建首保工单，不应再派新单");
    }
}
