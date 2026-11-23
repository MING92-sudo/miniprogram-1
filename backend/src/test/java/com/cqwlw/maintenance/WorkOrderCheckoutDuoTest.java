package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
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
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkOrderCheckoutDuoTest {

    private static final String EMP_LI = "emp_li";
    private static final String PID_LI = "pid_li";
    private static final String PID_WANG = "pid_wang";
    private static final String POOLED_ORDER = "wo_pooled";
    private static final String LEGACY_ORDER = "wo_legacy";
    private static final String CHECKLIST =
            "[{\"id\":\"it_1\",\"name\":\"曳引机\",\"isKey\":false,\"photoRequired\":false,\"result\":\"NORMAL\"}]";
    private static final String ASSISTANT_CHECKIN = "{\"role\":\"ASSISTANT\",\"geoStatus\":\"ASSISTANT_EXEMPT\"}";

    private WorkOrderMapper orderMapper;
    private MaintainRecordMapper recordMapper;
    private PlatformReportService reportService;
    private FileStorageService fileStorage;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        when(orderMapper.completeCheckout(any(), any(), any(), any(), any(), any())).thenReturn(1);
        recordMapper = mock(MaintainRecordMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
        UseUnit uu = new UseUnit();
        uu.unitPrincipal = "单位负责人";
        uu.unitPrincipalPhone = "13800000002";
        uu.elevatorAdminister = "安全管理员";
        uu.elevatorAdministerPhone = "13800000003";
        uu.emergencyPhone = "023-12345678";
        when(useUnitMapper.selectById(any())).thenReturn(uu);
        AppProperties props = new AppProperties();
        props.setWorkDurationMinutes(30);
        fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        reportService = mock(PlatformReportService.class);
        service = new WorkOrderService(orderMapper, elevatorMapper, useUnitMapper,
                employeeMapper, companyMapper, recordMapper,
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                reportService, new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                fileStorage, TxTestSupport.noopTx(), props);

        Employee li = new Employee();
        li.id = EMP_LI;
        li.name = "李强";
        li.role = "WORKER";
        li.platformId = PID_LI;
        li.phone = "13800000000";
        when(employeeMapper.selectById(EMP_LI)).thenReturn(li);
        when(employeeMapper.selectList(any())).thenReturn(List.of(li));
        Company company = new Company();
        company.workMenegerName = "维保经理";
        company.workMenegerPhone = "13800000004";
        when(companyMapper.selectList(any())).thenReturn(List.of(company));
        when(elevatorMapper.selectById("el_1")).thenReturn(completeElevator());
    }

    @Test
    void pooledOrderWithoutAssistantRejected() {
        WorkOrder o = pooledOrder();
        o.assistantName = null;
        o.assistantPlatformId = null;
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(POOLED_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("请选择配合人员"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void pooledOrderSoloMarkerNotHonored() {
        WorkOrder o = pooledOrder();
        o.assistantName = "无";
        o.assistantPlatformId = "";
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(POOLED_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("请选择配合人员"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void pooledOrderAssistantNotCheckedInRejected() {
        WorkOrder o = pooledOrder();
        o.assistantName = "王五";
        o.assistantPlatformId = PID_WANG;
        o.assistantCheckinExtraJson = null;
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(POOLED_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("配合人员未签到"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void pooledOrderMissingAssistantSignatureRejected() {
        WorkOrder o = pooledOrder();
        o.assistantName = "王五";
        o.assistantPlatformId = PID_WANG;
        o.assistantCheckinExtraJson = ASSISTANT_CHECKIN;
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(POOLED_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("配合人员签名"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    /** 双人齐（已选+已签到+双签）→ 放行签退 */
    @Test
    void pooledOrderFullDuoCheckoutSucceeds() {
        WorkOrder o = pooledOrder();
        o.assistantName = "王五";
        o.assistantPlatformId = PID_WANG;
        o.assistantCheckinExtraJson = ASSISTANT_CHECKIN;
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        Map<String, Object> result = assertDoesNotThrow(() -> service.checkout(POOLED_ORDER,
                Map.of("signatureUrl", "https://x/sign.png",
                        "assistantSignatureUrl", "https://x/sign2.png"), EMP_LI));
        assertEquals(POOLED_ORDER, result.get("workOrderId"));
        assertEquals("DONE", o.status);
        verify(recordMapper).insert(any(MaintainRecord.class));
    }

    @Test
    void checkoutRejectsMissingPrincipalCheckin() {
        WorkOrder o = pooledOrder();
        o.checkinTime = null;
        o.checkinExtraJson = null;
        o.assistantName = "王五";
        o.assistantPlatformId = PID_WANG;
        o.assistantCheckinExtraJson = ASSISTANT_CHECKIN;
        when(orderMapper.selectById(POOLED_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(POOLED_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));

        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("主维保"), e.getMessage());
        assertTrue(e.getMessage().contains("未签到"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(orderMapper, never()).completeCheckout(any(), any(), any(), any(), any(), any());
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
        verify(reportService, never()).attemptUpload(any());
    }

    @Test
    void noRegionPoolOrderSoloCheckoutRejected() {
        WorkOrder o = legacyOrder(); // regionId 空（不限区域电梯的池化新单）
        o.assistantName = null;
        o.assistantPlatformId = null;
        o.checkinExtraJson = "{\"role\":\"PRINCIPAL\",\"claimedViaPool\":true}";
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(LEGACY_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("请选择配合人员"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    /** 2.6 workMan2Id 实测必填：存量单人也必须在签退前补配合人，不得生成不完整快照。 */
    @Test
    void legacySoloOrderRejectedBecausePlatformRequiresWorker2() {
        WorkOrder o = legacyOrder();
        o.assistantName = "无";
        o.assistantPlatformId = "";
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(LEGACY_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));

        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("workMan2Id"), e.getMessage());
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void legacyDuoOrderStillRequiresAssistantSignature() {
        WorkOrder o = legacyOrder();
        o.assistantName = "王五";
        o.assistantPlatformId = PID_WANG;
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(LEGACY_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("配合人员签名"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void explicitNewOrderRequiresDuoEvenWithoutRegion() {
        WorkOrder o = legacyOrder();
        o.duoRequired = Boolean.TRUE;
        o.assistantName = null;
        o.assistantPlatformId = null;
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        BizException e = assertThrows(BizException.class,
                () -> service.checkout(LEGACY_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("请选择配合人员"), e.getMessage());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
        verify(recordMapper, never()).insert(any(MaintainRecord.class));
    }

    @Test
    void submitItemRejectsUnknownResult() {
        WorkOrder o = legacyOrder();
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        for (String result : new String[]{"BOGUS", ""}) {
            Map<String, Object> body = Map.of(
                    "result", result,
                    "value", Map.of("not", "numeric"),
                    "photoFileIds", List.of("file-1"));
            BizException e = assertThrows(BizException.class,
                    () -> service.submitItem(LEGACY_ORDER, "it_1", body, EMP_LI));
            assertEquals(422, e.getCode());
            assertTrue(e.getMessage().contains("检查结果非法"), e.getMessage());
        }
        verify(fileStorage, never()).urlOf(any());
        verify(orderMapper, never()).updateById(any(WorkOrder.class));
    }

    private static WorkOrder pooledOrder() {
        WorkOrder o = base(POOLED_ORDER);
        o.regionId = "region_yubei";
        return o;
    }

    /** 存量工单：派单时代产生，无 regionId 快照 */
    private static WorkOrder legacyOrder() {
        return base(LEGACY_ORDER);
    }

    private static WorkOrder base(String id) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = "WO-" + id;
        o.elevatorId = "el_1";
        o.workType = "半月维保";
        o.workTypeCode = "HM";
        o.status = "PROCESSING";
        o.workerName = "李强";
        o.workerPlatformId = PID_LI;
        o.checkinTime = TimeUtil.now().minusMinutes(40);
        o.checkinExtraJson = "{\"role\":\"PRINCIPAL\"}";
        o.checklistJson = CHECKLIST;
        return o;
    }

    private static Elevator completeElevator() {
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorName = "1号楼客梯";
        el.elevatorCode = "EM-001";
        el.deviceCode = "DT-CQ-2024-001";
        el.regCode = "TSCQ5001120001";
        el.insideNumber = "KT-01";
        el.factoryNumber = "SGL20131212-1";
        el.useUnitId = "uu_1";
        return el;
    }
}
