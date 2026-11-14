package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.HandoverArchive;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.mapper.HandoverArchiveMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * C-12：三张移交表单导出（检查清单/整改清单/归档目录），版面照 docs/15 §2/§3.1/§4.1/§5。
 * A4 纵向、STSong-Light、每页重复表头、页脚「第 x 页/共 y 页」+「不替代监督检验」声明（B1）。
 */
@Service
public class HandoverPdfService {

    private final HandoverOrderMapper orderMapper;
    private final HandoverCheckMapper checkMapper;
    private final HandoverCheckItemMapper itemMapper;
    private final HandoverRectificationMapper rectMapper;
    private final HandoverArchiveMapper archiveMapper;

    public HandoverPdfService(HandoverOrderMapper orderMapper, HandoverCheckMapper checkMapper,
                              HandoverCheckItemMapper itemMapper, HandoverRectificationMapper rectMapper,
                              HandoverArchiveMapper archiveMapper) {
        this.orderMapper = orderMapper;
        this.checkMapper = checkMapper;
        this.itemMapper = itemMapper;
        this.rectMapper = rectMapper;
        this.archiveMapper = archiveMapper;
    }

    public byte[] export(String handoverId, String form) {
        HandoverOrder o = orderMapper.selectById(handoverId);
        if (o == null) throw new com.cqwlw.maintenance.common.BizException(404, "移交单不存在");
        try {
            BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font title = new Font(bf, 16, Font.BOLD);
            Font head = new Font(bf, 9, Font.BOLD);
            Font body = new Font(bf, 8, Font.NORMAL);
            Document doc = new Document(PageSize.A4, 51, 51, 42, 42);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer(bf, o.handoverNo == null ? o.id : o.handoverNo));
            doc.open();
            if ("RECT".equals(form)) {
                doc.add(new Paragraph("电梯安装移交不合格项整改清单", title));
                doc.add(rectTable(o, head, body));
            } else if ("INDEX".equals(form)) {
                doc.add(new Paragraph("安装移交归档目录（一梯一档）", title));
                doc.add(indexTable(o, head, body));
            } else {
                doc.add(new Paragraph("电梯安装移交维保检查清单", title));
                doc.add(new Paragraph("移交单号：" + (o.handoverNo == null ? o.id : o.handoverNo) + " · 依据 GB/T 10060—2023 表1（74 项）", body));
                doc.add(checkTable(o, head, body));
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出移交表单失败: " + e.getMessage(), e);
        }
    }

    private HandoverCheck checkOf(String handoverId) {
        List<HandoverCheck> cs = checkMapper.selectList(new LambdaQueryWrapper<HandoverCheck>()
                .eq(HandoverCheck::getHandoverId, handoverId).orderByDesc(HandoverCheck::getCheckedAt));
        return cs.isEmpty() ? null : cs.get(0);
    }

    private PdfPTable checkTable(HandoverOrder o, Font head, Font body) {
        PdfPTable t = new PdfPTable(new float[]{5, 8, 9, 20, 30, 5, 6, 8, 5});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : new String[]{"序号", "项类", "条款", "检查项目", "检查要求", "重要", "结果", "实测值", "照片"}) {
            t.addCell(cell(h, head, true));
        }
        HandoverCheck c = checkOf(o.id);
        if (c != null) {
            List<HandoverCheckItem> items = itemMapper.selectList(new LambdaQueryWrapper<HandoverCheckItem>()
                    .eq(HandoverCheckItem::getCheckId, c.id).orderByAsc(HandoverCheckItem::getItemNo));
            for (HandoverCheckItem it : items) {
                t.addCell(cell(String.valueOf(it.itemNo), body, false));
                t.addCell(cell(nz(it.itemGroup), body, false));
                t.addCell(cell(nz(it.clauseNo), body, false));
                t.addCell(cell(nz(it.itemName), body, false));
                t.addCell(cell(nz(it.requirement), body, false));
                t.addCell(cell(Boolean.TRUE.equals(it.important) ? "☆" : "—", body, false));
                t.addCell(cell(resultMark(it.result), body, false));
                t.addCell(cell(nz(it.measuredValue), body, false));
                t.addCell(cell(photoCount(it.photoFileIds), body, false));
            }
        }
        return t;
    }

    private PdfPTable rectTable(HandoverOrder o, Font head, Font body) {
        PdfPTable t = new PdfPTable(new float[]{8, 8, 9, 20, 25, 10, 10, 10});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : new String[]{"整改序号", "检查序号", "条款", "检查项目", "不合格描述", "责任方", "整改期限", "复验"}) {
            t.addCell(cell(h, head, true));
        }
        List<HandoverRectification> rs = rectMapper.selectList(new LambdaQueryWrapper<HandoverRectification>()
                .eq(HandoverRectification::getHandoverId, o.id).orderByAsc(HandoverRectification::getItemNo));
        for (HandoverRectification r : rs) {
            t.addCell(cell("R" + r.itemNo, body, false));
            t.addCell(cell(String.valueOf(r.itemNo), body, false));
            t.addCell(cell("", body, false));
            t.addCell(cell("", body, false));
            t.addCell(cell(nz(r.rectMeasure), body, false));
            t.addCell(cell(nz(r.respParty), body, false));
            t.addCell(cell(nz(r.dueDate), body, false));
            t.addCell(cell(resultMark(r.recheckResult), body, false));
        }
        return t;
    }

    private PdfPTable indexTable(HandoverOrder o, Font head, Font body) {
        PdfPTable t = new PdfPTable(new float[]{8, 40, 12, 20, 20});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : new String[]{"序号", "归档文件", "类别", "文件编号", "SHA-256 前8"}) {
            t.addCell(cell(h, head, true));
        }
        List<HandoverArchive> as = archiveMapper.selectList(new LambdaQueryWrapper<HandoverArchive>()
                .eq(HandoverArchive::getHandoverId, o.id).orderByAsc(HandoverArchive::getSeq));
        for (HandoverArchive a : as) {
            t.addCell(cell(String.format("%02d", a.seq), body, false));
            t.addCell(cell(nz(a.category), body, false));
            t.addCell(cell(nz(a.category), body, false));
            t.addCell(cell(nz(a.fileId), body, false));
            t.addCell(cell(a.sha256 == null ? "—" : a.sha256.substring(0, 8), body, false));
        }
        return t;
    }

    private static PdfPCell cell(String s, Font f, boolean header) {
        PdfPCell c = new PdfPCell(new Phrase(s == null ? "—" : s, f));
        if (header) c.setBackgroundColor(new java.awt.Color(0xF2, 0xF2, 0xF2));
        c.setPadding(3);
        return c;
    }

    private static String resultMark(String r) {
        if ("PASS".equals(r)) return "√";
        if ("FAIL".equals(r)) return "×";
        if ("NA".equals(r)) return "—";
        return "—";
    }

    private static String photoCount(String ids) {
        if (ids == null || ids.isBlank()) return "—";
        return String.valueOf(ids.split(",").length);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /** 页脚：第 x 页/共 y 页 + 不替代监督检验声明（B1）。 */
    private static class Footer extends com.lowagie.text.pdf.PdfPageEventHelper {
        private final BaseFont bf;
        private final String no;
        private com.lowagie.text.pdf.PdfTemplate tpl;

        Footer(BaseFont bf, String no) {
            this.bf = bf;
            this.no = no;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            tpl = writer.getDirectContent().createTemplate(60, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            cb.beginText();
            cb.setFontAndSize(bf, 7.5f);
            cb.showTextAligned(Element.ALIGN_LEFT, "本清单为内部移交把关，不替代监督检验 · 单号 " + no,
                    document.left(), document.bottom() - 12, 0);
            cb.showTextAligned(Element.ALIGN_RIGHT, "第 " + writer.getPageNumber() + " 页",
                    document.right(), document.bottom() - 12, 0);
            cb.endText();
        }
    }
}