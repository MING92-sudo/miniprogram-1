package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
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
import com.cqwlw.maintenance.service.HandoverService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-13：移交内部审核（PENDING_INST_REVIEW）与作废（VOIDED）。
 * 资料齐全提交进入待安装部审核；安装主管审核通过→INSPECTING、退回→DOC_SUBMITTING（原因+期限必填）；
 * 任意未生效态可作废（原因必填，ARCHIVED/VOIDED 不可作废）。审核/作废留痕操作人。
 */
class HandoverReviewVoidTest {

    private final HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);

    private HandoverService service() {
        return new HandoverService(orderMapper, mock(HandoverCheckMapper.class),
                mock(HandoverCheckItemMapper.class), mock(HandoverRectificationMapper.class),
                mock(HandoverSignMapper.class), mock(ElevatorMapper.class), employeeMapper,
                mock(WorkOrderMapper.class), mock(DispatchService.class));
    }

    private HandoverOrder order(String status) {
        HandoverOrder o = new HandoverOrder();
        o.id = "ho_1";
        o.status = status;
        when(orderMapper.selectById("ho_1")).thenReturn(o);
        return o;
    }

    private void actor() {
        Employee e = new Employee();
        e.id = "emp_im";
        e.name = "安装主管";
        e.role = "INST_MGR";
        when(employeeMapper.selectById("emp_im")).thenReturn(e);
    }

    private HandoverOrder submitted() {
        HandoverOrder o = service().init(Map.of("projectName", "某项目"));
        when(orderMapper.selectById(o.id)).thenReturn(o);
        service().submitDocs(o.id, Map.of(
                "supervisionReportFileId", "f1",
                "maintenanceManualFileId", "f2",
                "qualityCertificateFileId", "f3"));
        return o;
    }

    /** 资料齐全提交 → 待安装部审核（不再直达 INSPECTING） */
    @Test
    void submitDocsEntersPendingInstReview() {
        HandoverOrder o = submitted();
        assertEquals("PENDING_INST_REVIEW", o.status, "资料齐全应进入待安装部审核");
    }

    /** 审核通过 → INSPECTING，留痕审核人/时间 */
    @Test
    void reviewApproveMovesToInspecting() {
        order("PENDING_INST_REVIEW");
        actor();
        HandoverOrder o = service().review("ho_1", Map.of("action", "APPROVE"), "emp_im");
        assertEquals("INSPECTING", o.status);
        assertEquals("emp_im", o.reviewById);
        assertEquals("安装主管", o.reviewByName);
        assertNotNull(o.reviewedAt);
    }

    /** 审核退回 → DOC_SUBMITTING，原因+期限留痕 */
    @Test
    void reviewRejectReturnsToDocSubmitting() {
        order("PENDING_INST_REVIEW");
        actor();
        HandoverOrder o = service().review("ho_1", Map.of(
                "action", "REJECT", "remark", "资料模糊需重传", "deadline", "2026-11-20"), "emp_im");
        assertEquals("DOC_SUBMITTING", o.status);
        assertEquals("资料模糊需重传", o.reviewRemark);
        assertEquals("2026-11-20", o.reviewDeadline);
    }

    /** 退回缺原因 → 422 */
    @Test
    void reviewRejectRequiresReason() {
        order("PENDING_INST_REVIEW");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().review("ho_1",
                Map.of("action", "REJECT", "deadline", "2026-11-20"), "emp_im"));
        assertEquals(422, e.getCode());
    }

    /** 退回缺期限 → 422 */
    @Test
    void reviewRejectRequiresDeadline() {
        order("PENDING_INST_REVIEW");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().review("ho_1",
                Map.of("action", "REJECT", "remark", "需重传"), "emp_im"));
        assertEquals(422, e.getCode());
    }

    /** 非待审核态审核 → 422 */
    @Test
    void reviewWrongStatusBlocked() {
        order("INSPECTING");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().review("ho_1",
                Map.of("action", "APPROVE"), "emp_im"));
        assertEquals(422, e.getCode());
    }

    /** 作废 → VOIDED，原因+操作人留痕 */
    @Test
    void voidMovesToVoided() {
        order("DOC_SUBMITTING");
        actor();
        HandoverOrder o = service().voidHandover("ho_1", Map.of("reason", "项目取消"), "emp_im");
        assertEquals("VOIDED", o.status);
        assertEquals("项目取消", o.voidReason);
        assertEquals("emp_im", o.voidById);
        assertNotNull(o.voidedAt);
    }

    /** 作废缺原因 → 422 */
    @Test
    void voidRequiresReason() {
        order("DOC_SUBMITTING");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().voidHandover("ho_1", Map.of(), "emp_im"));
        assertEquals(422, e.getCode());
    }

    /** 已归档不可作废 → 422 */
    @Test
    void voidArchivedBlocked() {
        order("ARCHIVED");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().voidHandover("ho_1",
                Map.of("reason", "项目取消"), "emp_im"));
        assertEquals(422, e.getCode());
    }

    /** 已作废不可重复作废 → 422 */
    @Test
    void voidAlreadyVoidedBlocked() {
        order("VOIDED");
        actor();
        BizException e = assertThrows(BizException.class, () -> service().voidHandover("ho_1",
                Map.of("reason", "再次作废"), "emp_im"));
        assertEquals(422, e.getCode());
    }
}