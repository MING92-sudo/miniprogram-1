package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.UnitRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M1：签字 token 只取 X-Share-Token 头（query 会进 access log）；小程序全量替换后不再兼容 query。 */
class UnitRecordControllerShareTokenTest {

    private UnitRecordService recordService;
    private IdempotencyService idempotency;
    private RecordLifecycleService lifecycle;
    private UnitRecordController controller;

    @BeforeEach
    void setUp() {
        recordService = mock(UnitRecordService.class);
        idempotency = mock(IdempotencyService.class);
        lifecycle = mock(RecordLifecycleService.class);
        controller = new UnitRecordController(recordService, idempotency, lifecycle,
                mock(SysUserRoleMapper.class));
    }

    private MockHttpServletRequest req(String header) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (header != null) {
            r.addHeader("X-Share-Token", header);
        }
        return r;
    }

    @Test
    void signViewUsesHeaderToken() {
        controller.signView("ur_1", req("sg-header"));

        verify(recordService).signView("ur_1", "sg-header");
    }

    @Test
    void signViewWithoutHeaderPassesNull() {
        controller.signView("ur_1", req(null));

        verify(recordService).signView("ur_1", null);
    }

    @Test
    void blankHeaderPassesNull() {
        controller.signView("ur_1", req("  "));

        verify(recordService).signView("ur_1", null);
    }

    @Test
    void confirmByTokenUsesHeaderToken() {
        when(idempotency.executeExternal(any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(3)).get());
        when(recordService.confirmByToken(eq("ur_1"), eq("sg-header"), any())).thenReturn(Map.of("ok", true));

        Object out = controller.confirmByToken("ur_1", Map.of(), "idem-1", req("sg-header")).getData();

        assertEquals(Map.of("ok", true), out);
        verify(recordService).confirmByToken(eq("ur_1"), eq("sg-header"), any());
    }

    @Test
    void refuseByTokenUsesHeaderToken() {
        when(idempotency.executeDb(any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(3)).get());

        controller.refuseByToken("ur_1", Map.of("reason", "照片不清晰"), "idem-2", req("sg-header"));

        verify(lifecycle).refuseByToken("ur_1", "sg-header", "", "照片不清晰");
    }

    @Test
    void signatureUploadUsesHeaderToken() throws Exception {
        controller.signatureUpload("ur_1", mock(org.springframework.web.multipart.MultipartFile.class),
                req("sg-header"));

        verify(recordService).signatureUploadByToken(eq("ur_1"), eq("sg-header"), any(), any());
    }

    @Test
    void signatureUploadWithoutTokenPassesNullThrough() throws Exception {
        controller.signatureUpload("ur_1", mock(org.springframework.web.multipart.MultipartFile.class),
                req(null));

        verify(recordService).signatureUploadByToken(eq("ur_1"), isNull(), any(), any());
    }
}
