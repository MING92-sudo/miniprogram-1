package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.HandoverArchive;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.service.HandoverChecklistService;
import com.cqwlw.maintenance.service.HandoverPdfService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * C-12：74 项移交检查清单/整改清单/归档目录端点（docs/15 §6.2）。
 * 判定/1:1 整改/逐条复验/NA 审批/SHA-256 归档在 HandoverChecklistService；三张表单导出 HandoverPdfService。
 */
@RestController
public class HandoverChecklistController {

    private final HandoverChecklistService checklist;
    private final HandoverPdfService pdf;
    private final com.cqwlw.maintenance.mapper.HandoverRectificationMapper rectMapper;

    public HandoverChecklistController(HandoverChecklistService checklist, HandoverPdfService pdf,
                                       com.cqwlw.maintenance.mapper.HandoverRectificationMapper rectMapper) {
        this.checklist = checklist;
        this.pdf = pdf;
        this.rectMapper = rectMapper;
    }

    @GetMapping("/handovers/{id}/checklist")
    public ApiResponse<Map<String, Object>> getChecklist(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        return ApiResponse.ok(checklist.getChecklist(id));
    }

    @PostMapping("/handovers/{id}/checklist/submit")
    public ApiResponse<HandoverOrder> submit(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_INSPECT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> cond = (Map<String, Object>) body.get("conditional");
        return ApiResponse.ok(checklist.submitChecklist(id, items == null ? List.of() : items, cond));
    }

    @GetMapping("/handovers/{id}/rectifications")
    public ApiResponse<List<HandoverRectification>> rectifications(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        return ApiResponse.ok(rectMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HandoverRectification>()
                        .eq(HandoverRectification::getHandoverId, id)
                        .orderByAsc(HandoverRectification::getItemNo)));
    }

    @PostMapping("/handovers/{id}/rectifications/{rectId}/rectify")
    public ApiResponse<HandoverRectification> rectify(@PathVariable String id, @PathVariable String rectId,
                                                      @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_RECTIFY);
        return ApiResponse.ok(checklist.rectifyItem(id, rectId, body));
    }

    @PostMapping("/handovers/{id}/rectifications/{rectId}/recheck")
    public ApiResponse<HandoverOrder> recheck(@PathVariable String id, @PathVariable String rectId,
                                              @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_REVIEW);
        return ApiResponse.ok(checklist.recheckItem(id, rectId, body));
    }

    @PostMapping("/handovers/{id}/na-approve")
    public ApiResponse<HandoverSign> naApprove(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.HANDOVER_REVIEW);
        return ApiResponse.ok(checklist.naApprove(id, body));
    }

    @GetMapping("/handovers/{id}/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id, @RequestParam(defaultValue = "CHECK") String form,
                                            HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_EXPORT);
        byte[] data = pdf.export(id, form);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=handover-" + form + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(data);
    }

    @GetMapping("/elevators/{id}/archive")
    public ApiResponse<List<HandoverArchive>> archive(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        return ApiResponse.ok(checklist.archive(id));
    }

    /** C-12：按移交单取一梯一档归档目录（管理端移交详情用，幂等生成 7 条 + SHA-256）。 */
    @GetMapping("/handovers/{id}/archive")
    public ApiResponse<List<HandoverArchive>> handoverArchive(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.HANDOVER_VIEW);
        return ApiResponse.ok(checklist.archive(id));
    }
}