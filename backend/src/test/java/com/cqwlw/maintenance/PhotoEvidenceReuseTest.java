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
import com.cqwlw.maintenance.service.EvidenceTokenService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检查项照片取证的「已存证沿用」：修改已保存项时历史照片不再有令牌（令牌不外发），
 * 此时按 fileId 复用服务端自己落库的存证；而本项从未验过签的 fileId 没有存证，
 * 必须仍然 fail closed。客户端不得凭回传坐标与时间伪造存证。
 */
class PhotoEvidenceReuseTest {

    private WorkOrderMapper orderMapper;
    private EmployeeMapper employeeMapper;
    private EvidenceTokenService evidenceTokenService;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        evidenceTokenService = mock(EvidenceTokenService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        service = new WorkOrderService(
                orderMapper,
                mock(ElevatorMapper.class),
                mock(UseUnitMapper.class),
                employeeMapper,
                mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class),
                mock(FaultMapper.class),
                mock(InspectRecordMapper.class),
                mock(com.cqwlw.maintenance.service.ChecklistService.class),
                mock(ApprovalService.class),
                evidenceTokenService,
                mock(FileStorageService.class),
                mock(PlatformReportService.class),
                new AppProperties(),
                currentUser);
        when(currentUser.roleOrNull()).thenReturn("WORKER");

        Employee me = new Employee();
        me.id = "e_p";
        me.name = "张伟";
        me.platformId = "P_PRINCIPAL";
        me.role = "WORKER";
        when(currentUser.requireEmployeeId()).thenReturn("e_p");
        when(employeeMapper.selectById("e_p")).thenReturn(me);
    }

    /** 已保存过一张照片（服务端存证）的检查项工单 */
    private WorkOrder savedItemOrder(String storedEvidenceJson) {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.orderNo = "WO-1";
        o.status = "PROCESSING";
        o.workTypeCode = "HM";
        o.elevatorId = "el_1";
        o.workerName = "张伟";
        o.workerPlatformId = "P_PRINCIPAL";
        o.assistantName = "";
        o.checklistJson = "[{\"id\":\"i1\",\"name\":\"项一\",\"photoEvidence\":" + storedEvidenceJson + "}]";
        when(orderMapper.selectById("wo_1")).thenReturn(o);
        when(orderMapper.selectOne(any())).thenReturn(o);
        return o;
    }

    private Map<String, Object> body(String fileId, String token) {
        Map<String, Object> b = new HashMap<>();
        b.put("result", "OK");
        b.put("photoFileIds", List.of(fileId));
        b.put("photoEvidence", List.of(Map.of("evidenceToken", token == null ? "" : token)));
        return b;
    }

    private static String storedRec(String fileId, String shotAt, double lat, double lng) {
        return "{\"fileId\":\"" + fileId + "\",\"shotAt\":\"" + shotAt
                + "\",\"latitude\":" + lat + ",\"longitude\":" + lng + ",\"evidenceNonce\":\"n1\"}";
    }

    @Test
    void reusesStoredEvidenceWhenEditingSavedItem() {
        WorkOrder o = savedItemOrder("[" + storedRec("file_old", "2026-10-02 10:00:00", 29.5, 106.5) + "]");

        // 历史照片无令牌，只有服务端已落库的存证可依
        Map<String, Object> resp = service.submitItem("wo_1", "i1", body("file_old", null));

        assertEquals(Boolean.TRUE, resp.get("ok"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = JsonUtil.readList(o.checklistJson);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) items.get(0).get("photoEvidence");
        assertEquals(1, evidence.size());
        // 存证的坐标与拍摄时间取自服务端库中记录，不是客户端回传
        assertEquals("2026-10-02 10:00:00", evidence.get(0).get("shotAt"));
        assertEquals(29.5, evidence.get(0).get("latitude"));
        assertEquals("n1", evidence.get(0).get("evidenceNonce"));
    }

    @Test
    void failsClosedForPhotoNeverVerifiedOnThisItem() {
        savedItemOrder("[" + storedRec("file_old", "2026-10-02 10:00:00", 29.5, 106.5) + "]");

        BizException e = assertThrows(BizException.class,
                () -> service.submitItem("wo_1", "i1", body("file_other", null)));

        assertEquals(422, e.getCode());
        assertEquals(true, e.getMessage().contains("取证令牌"));
    }

    @Test
    void stillVerifiesFreshTokenInsteadOfReusingStored() {
        WorkOrder o = savedItemOrder("[" + storedRec("file_old", "2026-10-02 10:00:00", 29.5, 106.5) + "]");
        Map<String, Object> payload = new HashMap<>();
        payload.put("st", 1750000000000L / 1000);
        payload.put("lat", 1.0);
        payload.put("lng", 2.0);
        payload.put("n", "fresh");
        when(evidenceTokenService.verifyOnly(anyString(), anyString(), anyString())).thenReturn(payload);

        service.submitItem("wo_1", "i1", body("file_new", "tok_fresh"));

        verify(evidenceTokenService).verifyOnly(eq("tok_fresh"), eq("wo_1"), eq("i1"));
        assertEquals("fresh", evidenceOf(o).get(0).get("evidenceNonce"));
        assertEquals(1.0, evidenceOf(o).get(0).get("latitude"));
    }

    @Test
    void newlyShotPhotoWithoutTokenStillRejected() {
        savedItemOrder("[]");

        BizException e = assertThrows(BizException.class,
                () -> service.submitItem("wo_1", "i1", body("file_new", null)));

        assertEquals(422, e.getCode());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evidenceOf(WorkOrder o) {
        List<Map<String, Object>> items = JsonUtil.readList(o.checklistJson);
        return (List<Map<String, Object>>) items.get(0).get("photoEvidence");
    }
}