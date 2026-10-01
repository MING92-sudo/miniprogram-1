package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.RecordPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 管理端·维保记录导出：
 * GET /admin/records/{id}/export-pdf → 维保记录 PDF（含照片/签字/签到签退时间）。
 * JWT 鉴权（/admin/* 不在白名单，默认拦截）。
 */
@RestController
public class AdminRecordController {

    private final MaintainRecordMapper recordMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final RecordPdfService pdfService;

    public AdminRecordController(MaintainRecordMapper recordMapper, ElevatorMapper elevatorMapper,
                                 UseUnitMapper useUnitMapper, RecordPdfService pdfService) {
        this.recordMapper = recordMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.pdfService = pdfService;
    }

    @GetMapping("/admin/records/{id}/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        MaintainRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
        }
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, r.elevatorCode).last("LIMIT 1"));
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        byte[] pdf = pdfService.render(r, el, uu);
        String filename = URLEncoder.encode("维保记录-" + r.originalRecordId + ".pdf",
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
