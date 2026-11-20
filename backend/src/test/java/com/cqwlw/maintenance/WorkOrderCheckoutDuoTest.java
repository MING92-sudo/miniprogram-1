package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
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

/**
 * 签退恒双人门禁（2026-11-20 区域抢单池设计 D8/M8，docs/superpowers/specs §4.2）：
 * 池化新单（V57 起派单落 regionId 快照）签退必须 ①已选配合人（assistantPlatformId 非空）、
 * ②配合人已签到（assistantCheckinExtraJson 非空）、③双签齐全（既有 hasAssistant 分支升级为恒触发）；
 * 三道门禁均先于检查项/时长校验与任何写操作。
 * 存量在途工单（regionId 空，含派单时代 assistantName="无" 的单人豁免路径，M8）维持旧口径放行。
 */
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
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        recordMapper = mock(MaintainRecordMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        AppProperties props = new AppProperties();
        props.setWorkDurationMinutes(30);
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.urlOf(any())).thenReturn("");
        service = new WorkOrderService(orderMapper, elevatorMapper, mock(UseUnitMapper.class),
                employeeMapper, companyMapper, recordMapper,
                mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), new EmployeeScopeService(employeeMapper),
                new CheckinThresholdService(mock(SysParamMapper.class), props),
                fileStorage, TxTestSupport.noopTx(), props);

        Employee li = new Employee();
        li.id = EMP_LI;
        li.name = "李强";
        li.role = "WORKER";
        li.platformId = PID_LI;
        li.phone = "13800000000";
        when(employeeMapper.selectById(EMP_LI)).thenReturn(li);
        // buildReportPayload（B1）按 platformId 强关联取 recorderPhone
        when(employeeMapper.selectList(any())).thenReturn(List.of(li));
        when(companyMapper.selectList(any())).thenReturn(List.of(new Company()));
        when(elevatorMapper.selectById("el_1")).thenReturn(completeElevator());
    }

    /** D8 门禁①：池化新单未选配合人 → 422「请选择配合人员」，不落任何库 */
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

    /** D8 恒触发：池化新单上即使残留单人豁免标记（assistantName="无"）也不得放行 */
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

    /** D8 门禁②：已选配合人但配合人未签到 → 422「配合人员未签到」，不落任何库 */
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

    /** D8 门禁③：配合人已选已签到但缺配合人签名 → 422 双签缺一，不落任何库 */
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

    /** D8 判定双信号：不限区域池化新单（regionId 空但签到认领打标 claimedViaPool）未选配合人 → 422 且零写库 */
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

    /** M8 回归：存量在途单人豁免工单（regionId 空 + assistantName="无"）不受新门禁拦截，按旧口径放行 */
    @Test
    void legacySoloOrderStillAllowed() {
        WorkOrder o = legacyOrder();
        o.assistantName = "无";
        o.assistantPlatformId = "";
        when(orderMapper.selectById(LEGACY_ORDER)).thenReturn(o);

        Map<String, Object> result = assertDoesNotThrow(
                () -> service.checkout(LEGACY_ORDER, Map.of("signatureUrl", "https://x/sign.png"), EMP_LI));
        assertEquals(LEGACY_ORDER, result.get("workOrderId"));
        assertEquals("DONE", o.status);
        verify(recordMapper).insert(any(MaintainRecord.class));
    }

    /** M8 回归：存量带配合人工单（regionId 空）维持旧口径——缺配合人签名仍 422 */
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

    /** 池化新单（V57 起派单落 regionId 快照）：主维保已认领，PROCESSING */
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
        o.checklistJson = CHECKLIST;
        return o;
    }

    /** 设备信息档案齐备（上传维保记录的 5 项必传项，docs/01 §3.4.1） */
    private static Elevator completeElevator() {
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorName = "1号楼客梯";
        el.elevatorCode = "EM-001";
        el.deviceCode = "DT-CQ-2024-001";
        el.regCode = "TSCQ5001120001";
        el.insideNumber = "KT-01";
        el.factoryNumber = "SGL20131212-1";
        return el;
    }
}
