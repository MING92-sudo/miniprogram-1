package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.HandoverArchiveSupport;
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoverDraftTest {

    private HandoverOrderMapper orderMapper;
    private HandoverArchiveSupport archiveSupport;
    private HandoverService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(HandoverOrderMapper.class);
        archiveSupport = mock(HandoverArchiveSupport.class);
        service = new HandoverService(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class), mock(ElevatorMapper.class), mock(EmployeeMapper.class),
                mock(WorkOrderMapper.class), mock(DispatchService.class), archiveSupport);
    }

    private static Map<String, Object> validDraft() {
        Map<String, Object> body = new HashMap<>();
        body.put("projectName", "渝北区某项目");
        body.put("useUnitId", "uu_1");
        body.put("factoryNumber", "SGL-2026-001");
        body.put("insideNumber", "KT-01");
        body.put("supervisionReportFileId", "file_supervision");
        body.put("maintenanceManualFileId", "file_manual");
        body.put("qualityCertificateFileId", "file_quality");
        return body;
    }

    @Test
    void initRejectsEachMissingRequiredDocument() {
        for (String field : List.of("supervisionReportFileId", "maintenanceManualFileId", "qualityCertificateFileId")) {
            Map<String, Object> body = validDraft();
            body.remove(field);
            BizException e = assertThrows(BizException.class, () -> service.init(body), field);
            assertEquals(422, e.getCode());
            verify(orderMapper, never()).insert(any(HandoverOrder.class));
        }
    }

    @Test
    void initRejectsMissingFactoryNumber() {
        Map<String, Object> body = validDraft();
        body.remove("factoryNumber");
        BizException e = assertThrows(BizException.class, () -> service.init(body));
        assertEquals(422, e.getCode());
        verify(orderMapper, never()).insert(any(HandoverOrder.class));
    }

    @Test
    void initRejectsMissingInsideNumber() {
        Map<String, Object> body = validDraft();
        body.remove("insideNumber");
        BizException e = assertThrows(BizException.class, () -> service.init(body));
        assertEquals(422, e.getCode());
        verify(orderMapper, never()).insert(any(HandoverOrder.class));
    }

    @Test
    void initCreatesPendingReviewAndIgnoresClientElevatorId() {
        Map<String, Object> body = validDraft();
        body.put("elevatorId", "el_client_forged");

        HandoverOrder created = service.init(body);

        assertEquals("PENDING_INST_REVIEW", created.status);
        assertEquals("uu_1", created.useUnitId);
        assertEquals("file_supervision", created.supervisionReportFileId);
        assertEquals("file_manual", created.maintenanceManualFileId);
        assertEquals("file_quality", created.qualityCertificateFileId);
        assertNull(created.elevatorId);
    }

    @Test
    void initRejectsMissingUseUnit() {
        Map<String, Object> body = validDraft();
        body.remove("useUnitId");
        BizException e = assertThrows(BizException.class, () -> service.init(body));
        assertEquals(422, e.getCode());
        verify(orderMapper, never()).insert(any(HandoverOrder.class));
    }

    @Test
    void initRejectsUnknownUseUnit() {
        doThrow(new BizException(422, "使用单位不存在")).when(archiveSupport).requireUseUnit("uu_1");
        assertThrows(BizException.class, () -> service.init(validDraft()));
        verify(orderMapper, never()).insert(any(HandoverOrder.class));
    }

    @Test
    void optionsExposeUseUnitsForDraft() {
        when(archiveSupport.useUnitOptions()).thenReturn(List.of(Map.of("id", "uu_1", "unitName", "世纪物业")));
        assertEquals(Map.of("useUnits", List.of(Map.of("id", "uu_1", "unitName", "世纪物业"))),
                service.options());
    }
}
