package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.PlatformTokenService;
import com.cqwlw.maintenance.service.RecordPdfService;
import com.cqwlw.maintenance.service.UnitRecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRecordReadScopeTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
    }

    @Test
    void detailAndPdfRejectCrossGroupRecord() {
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        MaintainRecord record = new MaintainRecord();
        record.id = "r1";
        record.workerPlatformId = "pid_other";
        when(records.selectById("r1")).thenReturn(record);
        EmployeeScopeService scope = mock(EmployeeScopeService.class);
        Employee me = new Employee();
        me.id = "emp_1";
        when(scope.require("emp_1")).thenReturn(me);
        when(scope.visibleReadPlatformIds(me)).thenReturn(Set.of("pid_team"));
        AdminRecordController controller = new AdminRecordController(records, mock(ElevatorMapper.class),
                mock(UseUnitMapper.class), mock(RecordPdfService.class), mock(UnitRecordService.class), scope);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID)).thenReturn("emp_1");

        BizException detail = assertThrows(BizException.class, () -> controller.detail("r1", request));
        BizException pdf = assertThrows(BizException.class, () -> controller.exportPdf("r1", request));

        assertEquals(1403, detail.getCode());
        assertEquals(1403, pdf.getCode());
    }

    @Test
    void emptyReadScopeReturnsEmptyPageWithoutQuery() {
        AdminService service = new AdminService(mock(WorkOrderMapper.class), mock(MaintainRecordMapper.class),
                mock(RegUploadLogMapper.class), mock(ElevatorMapper.class), mock(EmployeeMapper.class),
                mock(FaultMapper.class), mock(PlatformTokenService.class), null);

        Map<String, Object> out = service.records(Map.of("page", "1", "size", "20"), Set.of());

        assertEquals(0, out.get("total"));
        assertEquals(List.of(), out.get("list"));
    }

    @Test
    void nonEmptyReadScopeAddsBothWorkerPredicates() {
        MaintainRecordMapper records = mock(MaintainRecordMapper.class);
        when(records.selectPage(any(), any())).thenReturn(new Page<>(1, 20));
        AdminService service = new AdminService(mock(WorkOrderMapper.class), records,
                mock(RegUploadLogMapper.class), mock(ElevatorMapper.class), mock(EmployeeMapper.class),
                mock(FaultMapper.class), mock(PlatformTokenService.class), null);

        service.records(Map.of("page", "1", "size", "20"), Set.of("pid_team"));

        ArgumentCaptor<LambdaQueryWrapper<MaintainRecord>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(records).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("worker_platform_id"), sql);
        assertTrue(sql.contains("assistant_platform_id"), sql);
    }

    @Test
    void recordEndpointsDeclareFineGrainedPermissions() throws Exception {
        RequirePerm detail = AdminRecordController.class
                .getMethod("detail", String.class, HttpServletRequest.class).getAnnotation(RequirePerm.class);
        RequirePerm export = AdminRecordController.class
                .getMethod("exportPdf", String.class, HttpServletRequest.class).getAnnotation(RequirePerm.class);
        assertEquals(PermissionMatrix.RECORD_VIEW, detail.value());
        assertEquals(PermissionMatrix.RECORD_EXPORT, export.value());
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("INST_PM", PermissionMatrix.RECORD_VIEW));
    }
}
