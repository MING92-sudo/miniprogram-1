package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.ExternalFilingDoc;
import com.cqwlw.maintenance.entity.ExternalFilingOrder;
import com.cqwlw.maintenance.entity.ExternalTakeoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverConfirmation;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.ExternalFilingDocMapper;
import com.cqwlw.maintenance.mapper.ExternalFilingOrderMapper;
import com.cqwlw.maintenance.mapper.ExternalTakeoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.ExternalTakeoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverConfirmationMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ContractService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.ExternalFilingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外接建档（W2，Task 7）：合同前置校验、缺件待补办不阻断、接管查验不合格不阻塞但责任归属必填、
 * 交接确认双签后归档（archive_source=2，首保 planTime=归档日+3 日）。
 */
class ExternalFilingServiceTest {

    private static final String CONTRACT_ID = "ct_1";

    private static final class Mocks {
        final ExternalFilingOrderMapper orderMapper = mock(ExternalFilingOrderMapper.class);
        final ExternalFilingDocMapper docMapper = mock(ExternalFilingDocMapper.class);
        final ExternalTakeoverCheckMapper checkMapper = mock(ExternalTakeoverCheckMapper.class);
        final ExternalTakeoverCheckItemMapper itemMapper = mock(ExternalTakeoverCheckItemMapper.class);
        final HandoverConfirmationMapper confirmMapper = mock(HandoverConfirmationMapper.class);
        final ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper = mock(com.cqwlw.maintenance.mapper.EmployeeMapper.class);
        final WorkOrderMapper workOrderMapper = mock(WorkOrderMapper.class);
        final DispatchService dispatchService = mock(DispatchService.class);
        final ContractService contractService = mock(ContractService.class);
        final ExternalFilingService service = new ExternalFilingService(orderMapper, docMapper, checkMapper,
                itemMapper, confirmMapper, elevatorMapper, employeeMapper, workOrderMapper, dispatchService, contractService);
    }

    private static MaintenanceContract contract(String useUnitId, LocalDate start, LocalDate end, String status) {
        MaintenanceContract c = new MaintenanceContract();
        c.id = CONTRACT_ID;
        c.contractNo = "HT-2026-001";
        c.useUnitId = useUnitId;
        c.startDate = start;
        c.endDate = end;
        c.status = status;
        return c;
    }

    private static Map<String, Object> initBody() {
        return Map.of("useUnitId", "uu_1", "contractId", CONTRACT_ID,
                "projectName", "锦绣花园", "elevatorCode", "EM-2026-100",
                "elevatorName", "锦绣花园 1# 梯", "category", "曳引与强制驱动电梯",
                "initiatorId", "emp_9", "initiatorName", "王经理");
    }

    /** 没有生效合同：不给 contractId → 422；合同起始日晚于建档日 → 422；合同属别家使用单位 → 422 */
    @Test
    void initRequiresActiveContract() {
        Mocks m = new Mocks();
        BizException noContract = assertThrows(BizException.class,
                () -> m.service.init(Map.of("useUnitId", "uu_1")));
        assertEquals(422, noContract.getCode());

        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().plusDays(1), LocalDate.now().plusYears(1), "ACTIVE"));
        BizException notYet = assertThrows(BizException.class, () -> m.service.init(initBody()));
        assertEquals(422, notYet.getCode());
        assertTrue(notYet.getMessage().contains("起始日"));

        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_other",
                LocalDate.now().minusDays(1), LocalDate.now().plusYears(1), "ACTIVE"));
        BizException wrongUnit = assertThrows(BizException.class, () -> m.service.init(initBody()));
        assertEquals(422, wrongUnit.getCode());
    }

    /** 同梯已有其它生效合同 → 422（不建单、不产生半成品） */
    @Test
    void initRejectsSecondActiveContract() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        Elevator existing = new Elevator();
        existing.id = "el_exist";
        existing.elevatorCode = "EM-2026-100";
        when(m.elevatorMapper.selectList(any())).thenReturn(List.of(existing));
        MaintenanceContract other = contract("uu_1", LocalDate.now().minusDays(5),
                LocalDate.now().plusYears(1), "ACTIVE");
        other.id = "ct_other";
        when(m.contractService.getActiveContractByElevator("el_exist")).thenReturn(other);

        BizException ex = assertThrows(BizException.class, () -> m.service.init(initBody()));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("已有生效合同"));
    }

    /** 缺件待补办：missing=true 不阻断，落 missing_flag=1，提交后进入 TAKEOVER_CHECKING */
    @Test
    void missingDocGeneratesBackfillFlag() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        ExternalFilingOrder o = m.service.init(initBody());
        when(m.orderMapper.selectById(o.id)).thenReturn(o);

        m.service.submitDocs(o.id, Map.of("docs", List.of(
                Map.of("docType", "USE_REG_CERT", "fileId", "file_1"),
                Map.of("docType", "MAINTENANCE_RECORD", "missing", true))));

        ArgumentCaptor<ExternalFilingDoc> captor = ArgumentCaptor.forClass(ExternalFilingDoc.class);
        verify(m.docMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<ExternalFilingDoc> docs = captor.getAllValues();
        assertTrue(docs.stream().anyMatch(d -> Boolean.TRUE.equals(d.missingFlag)
                && "MAINTENANCE_RECORD".equals(d.docType)));
        assertEquals("TAKEOVER_CHECKING", o.status);
    }

    /** 未标缺件又没传文件 → 422（防止空资料混进档案） */
    @Test
    void docWithoutFileOrMissingFlagIsRejected() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        ExternalFilingOrder o = m.service.init(initBody());
        when(m.orderMapper.selectById(o.id)).thenReturn(o);

        BizException ex = assertThrows(BizException.class, () -> m.service.submitDocs(o.id,
                Map.of("docs", List.of(Map.of("docType", "INSPECTION_REPORT")))));
        assertEquals(422, ex.getCode());
    }

    /** 接管查验：不合格项不阻塞（仍进 PENDING_CONFIRM），但责任归属必须落库 */
    @Test
    void takeoverCheckRecordsResponsibilityWithoutBlocking() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        ExternalFilingOrder o = m.service.init(initBody());
        when(m.orderMapper.selectById(o.id)).thenReturn(o);
        m.service.submitDocs(o.id, Map.of("docs", List.of(
                Map.of("docType", "USE_REG_CERT", "fileId", "file_1"))));

        m.service.submitTakeoverCheck(o.id, Map.of("items", List.of(
                Map.of("itemType", "BRAKE", "result", "FAIL",
                        "responsibility", "PREV_MAINT", "remark", "制动闸瓦磨损超限"),
                Map.of("itemType", "RUNNING", "result", "PASS"))));

        assertEquals("PENDING_CONFIRM", o.status);
        ArgumentCaptor<ExternalTakeoverCheckItem> captor =
                ArgumentCaptor.forClass(ExternalTakeoverCheckItem.class);
        verify(m.itemMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        ExternalTakeoverCheckItem brake = captor.getAllValues().stream()
                .filter(i -> "BRAKE".equals(i.itemType)).findFirst().orElseThrow();
        assertEquals("FAIL", brake.result);
        assertEquals("PREV_MAINT", brake.responsibility);
        // 未提交的其余 4 类自动补 NA，保证清单完整（6 类中已提交 BRAKE/RUNNING）
        assertEquals(4, captor.getAllValues().stream().filter(i -> "NA".equals(i.result)).count());
    }

    /** 不合格项不写责任归属 → 422（合规要求：责任必须落到具体一方） */
    @Test
    void failedItemWithoutResponsibilityIsRejected() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        ExternalFilingOrder o = m.service.init(initBody());
        when(m.orderMapper.selectById(o.id)).thenReturn(o);
        m.service.submitDocs(o.id, Map.of("docs", List.of(
                Map.of("docType", "USE_REG_CERT", "fileId", "file_1"))));

        BizException ex = assertThrows(BizException.class, () -> m.service.submitTakeoverCheck(o.id,
                Map.of("items", List.of(Map.of("itemType", "SAFETY_DEVICE", "result", "FAIL")))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("责任归属"));
    }

    /** 交接确认：对方签字必备 → 归档建电梯（archive_source=2）+ 首保工单（+3 日、source_filing_id 回指） */
    @Test
    void confirmArchivesElevatorAndPrecreatesFirstOrder() {
        Mocks m = new Mocks();
        when(m.contractService.get(CONTRACT_ID)).thenReturn(contract("uu_1",
                LocalDate.now().minusDays(10), LocalDate.now().plusYears(1), "ACTIVE"));
        ExternalFilingOrder o = m.service.init(initBody());
        when(m.orderMapper.selectById(o.id)).thenReturn(o);
        m.service.submitDocs(o.id, Map.of("docs", List.of(
                Map.of("docType", "USE_REG_CERT", "fileId", "file_1"))));
        m.service.submitTakeoverCheck(o.id, Map.of("items", List.of(
                Map.of("itemType", "RUNNING", "result", "PASS"))));

        WorkOrder first = new WorkOrder();
        first.id = "wo_first";
        when(m.dispatchService.precreateFirstOrder(any(Elevator.class), eq(LocalDate.now().plusDays(3))))
                .thenReturn(first);

        com.cqwlw.maintenance.entity.Employee actor = new com.cqwlw.maintenance.entity.Employee();
        actor.id = "emp_real";
        actor.name = "真实登录人";
        when(m.employeeMapper.selectById("emp_real")).thenReturn(actor);
        HandoverConfirmation conf = m.service.confirm(o.id, Map.of(
                "counterpartSignName", "刘管理员",
                "counterpartSignFileId", "file_sign",
                "keyToolCheck", "钥匙 2 把、盘车手轮齐全",
                "leftoverIssue", "制动闸瓦待更换（前维保单位负责）",
                "confirmedById", "emp_m1", "confirmedByName", "伪造请求体身份"), "emp_real");

        assertNotNull(conf.id);
        assertEquals("emp_real", conf.confirmedById);
        assertEquals("真实登录人", conf.confirmedByName);
        assertEquals("ARCHIVED", o.status);
        ArgumentCaptor<Elevator> elCaptor = ArgumentCaptor.forClass(Elevator.class);
        verify(m.elevatorMapper).insert(elCaptor.capture());
        Elevator el = elCaptor.getValue();
        assertEquals(2, el.archiveSource);
        assertEquals("EM-2026-100", el.elevatorCode);
        ArgumentCaptor<WorkOrder> woCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(m.workOrderMapper).updateById(woCaptor.capture());
        assertEquals(o.id, woCaptor.getValue().sourceFilingId);
        assertNotNull(o.elevatorId);
    }

    /** 归档幂等：已归档（elevatorId 非空）不重复建电梯 */
    @Test
    void archiveIsIdempotent() {
        Mocks m = new Mocks();
        ExternalFilingOrder o = new ExternalFilingOrder();
        o.id = "ef_1";
        o.elevatorId = "el_done";
        o.status = "ARCHIVED";
        when(m.orderMapper.selectById("ef_1")).thenReturn(o);

        m.service.archive("ef_1");
        verify(m.elevatorMapper, org.mockito.Mockito.never()).insert(any(Elevator.class));
    }

    /** 管理端列表（Task 14）：status 过滤 + 发起时间倒序 */
    @Test
    void listFiltersByStatusAndSortsByCreatedAt() {
        Mocks m = new Mocks();
        ExternalFilingOrder old = new ExternalFilingOrder();
        old.id = "ef_old";
        old.status = "PENDING_CONFIRM";
        old.createdAt = java.time.LocalDateTime.of(2026, 1, 1, 9, 0);
        ExternalFilingOrder recent = new ExternalFilingOrder();
        recent.id = "ef_new";
        recent.status = "PENDING_CONFIRM";
        recent.createdAt = java.time.LocalDateTime.of(2026, 1, 2, 9, 0);
        ExternalFilingOrder other = new ExternalFilingOrder();
        other.id = "ef_x";
        other.status = "DOC_COLLECTING";
        other.createdAt = java.time.LocalDateTime.of(2026, 1, 3, 9, 0);
        when(m.orderMapper.selectList(any())).thenReturn(List.of(old, recent, other));

        Map<String, Object> out = m.service.list(Map.of("status", "PENDING_CONFIRM"));
        java.util.List<?> rows = (java.util.List<?>) out.get("list");
        assertEquals(2, rows.size());
        assertEquals("ef_new", ((ExternalFilingOrder) rows.get(0)).id, "应按发起时间倒序");
        assertEquals(2, out.get("total"));
    }
}
