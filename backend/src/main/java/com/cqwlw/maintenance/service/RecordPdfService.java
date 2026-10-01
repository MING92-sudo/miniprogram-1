package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 维保记录 PDF 导出（docs/09 管理端一期·记录归档）：
 * 内容含基本信息（签到/签退时间、维保人员）、检查项明细、现场照片、
 * 维保人员与使用单位安全管理员签字。照片/签字经 URL 拉取，失败降级为文字占位。
 * 中文字体用 OpenPDF CID 字体 STSong-Light（UniGB-UCS2-H），不向仓库提交字体文件。
 */
@Service
public class RecordPdfService {

    private static final Logger log = LoggerFactory.getLogger(RecordPdfService.class);
    private static final Map<String, String> RESULT_TEXT =
            Map.of("NORMAL", "正常", "ABNORMAL", "异常", "NA", "不适用");
    private static final Map<String, String> REPORT_STATUS_TEXT =
            Map.of("REPORTED", "平台上报成功", "FAILED", "上报失败", "SUBMITTED", "待上报");

    private final RestTemplate restTemplate;
    private final AppFileMapper fileMapper;
    private final FileStorageService fileStorage;
    private final AppProperties props;

    public RecordPdfService(RestTemplate restTemplate, AppFileMapper fileMapper,
                            FileStorageService fileStorage, AppProperties props) {
        this.restTemplate = restTemplate;
        this.fileMapper = fileMapper;
        this.fileStorage = fileStorage;
        this.props = props;
    }

    /**
     * 允许抓取的绝对地址主机名白名单：仅本系统自有的 COS 桶域名。
     * 维保记录里的图片 URL 全部来自 FileStorageService.store（客户端不可控），
     * 但历史数据或人工导入可能残留任意地址，故导出时再做一次白名单兜底，
     * 避免把维保记录变成"服务器代抓任意 URL"的 SSRF 通道。
     */
    public boolean allowedHost(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null || props.getCosBucket() == null || props.getCosBucket().isEmpty()) {
                return false;
            }
            String suffix = ".cos." + props.getCosRegion() + ".myqcloud.com";
            return host.equalsIgnoreCase(props.getCosBucket() + suffix);
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] render(MaintainRecord r, Elevator el, UseUnit uu) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font title = font(16, Font.BOLD);
            Font label = font(9, Font.BOLD);
            Font value = font(9, Font.NORMAL);
            Font small = font(7.5f, Font.NORMAL);

            Paragraph head = new Paragraph("电梯维保记录", title);
            head.setAlignment(Element.ALIGN_CENTER);
            doc.add(head);
            Paragraph sub = new Paragraph("记录编号 " + nz(r.originalRecordId)
                    + "　生成时间 " + TimeUtil.format(TimeUtil.now()), small);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(10);
            doc.add(sub);

            // ── 基本信息表 ──
            PdfPTable info = table(4, label, value);
            info.addCell(cell("电梯名称", label));
            info.addCell(cell(nz(r.elevatorName), value));
            info.addCell(cell("电梯编码", label));
            info.addCell(cell(nz(r.elevatorCode), value));
            info.addCell(cell("设备代码", label));
            info.addCell(cell(el == null ? "" : nz(el.deviceCode), value));
            info.addCell(cell("使用单位", label));
            info.addCell(cell(uu == null ? "" : nz(uu.unitName), value));
            info.addCell(cell("维保类别", label));
            info.addCell(cell(nz(r.workType), value));
            info.addCell(cell("作业时长", label));
            info.addCell(cell(nz(r.duration), value));
            info.addCell(cell("签到时间", label));
            info.addCell(cell(fmt(r.checkinTime), value));
            info.addCell(cell("签退时间", label));
            info.addCell(cell(fmt(r.checkoutTime), value));
            info.addCell(cell("维保人员", label));
            info.addCell(cell(nz(r.workerName), value));
            info.addCell(cell("配合人员", label));
            info.addCell(cell(nz(r.assistantName), value));
            info.addCell(cell("使用单位负责人", label));
            info.addCell(cell(uu == null ? "" : nz(uu.unitPrincipal), value));
            info.addCell(cell("安全管理员", label));
            info.addCell(cell(el != null && notBlank(el.elevatorAdminister)
                    ? el.elevatorAdminister : (uu == null ? "" : nz(uu.elevatorAdminister)), value));
            info.addCell(cell("下次维保日期", label));
            info.addCell(cell(TimeUtil.formatDate(r.nextMaintenanceDate), value));
            info.addCell(cell("上报状态", label));
            info.addCell(cell(REPORT_STATUS_TEXT.getOrDefault(
                    nz(r.reportStatus), nz(r.reportStatus)), value));
            doc.add(info);

            // ── 检查项明细 ──
            List<Map<String, Object>> items = r.itemsJson == null ? List.of() : JsonUtil.readList(r.itemsJson);
            Paragraph itemTitle = new Paragraph("检查项明细（" + items.size() + " 项）", label);
            itemTitle.setSpacingBefore(10);
            itemTitle.setSpacingAfter(4);
            doc.add(itemTitle);
            PdfPTable itemsTable = new PdfPTable(new float[]{1.2f, 7f, 2f, 4f});
            itemsTable.setWidthPercentage(100);
            itemsTable.addCell(headCell("序号", label));
            itemsTable.addCell(headCell("检查项", label));
            itemsTable.addCell(headCell("结果", label));
            itemsTable.addCell(headCell("数值/备注", label));
            int seq = 1;
            for (Map<String, Object> item : items) {
                itemsTable.addCell(bodyCell(String.valueOf(seq++), small));
                itemsTable.addCell(bodyCell(nz(str(item.get("name"))), small));
                String result = str(item.get("result"));
                itemsTable.addCell(bodyCell(RESULT_TEXT.getOrDefault(result, nz(result)), small));
                String remark = firstNonBlank(
                        str(item.get("abnormalDesc")), str(item.get("valueText")),
                        item.get("value") == null ? null : ("读数 " + item.get("value")
                                + (item.get("valueUnit") == null ? "" : item.get("valueUnit"))),
                        str(item.get("skipReason")));
                itemsTable.addCell(bodyCell(nz(remark), small));
            }
            if (items.isEmpty()) {
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("无检查项明细", small));
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("—", small));
            }
            doc.add(itemsTable);

            // ── 签字区 ──
            Paragraph signTitle = new Paragraph("签字确认", label);
            signTitle.setSpacingBefore(10);
            signTitle.setSpacingAfter(4);
            doc.add(signTitle);
            PdfPTable signs = new PdfPTable(3);
            signs.setWidthPercentage(100);
            signs.addCell(signCell("维保人员签字（" + nz(r.workerName) + "）",
                    r.workerSignatureUrl, small));
            signs.addCell(signCell("配合人员签字（" + nz(r.assistantName) + "）",
                    r.assistantSignatureUrl, small));
            signs.addCell(signCell("使用单位安全管理员签字", r.signatureUrl, small));
            doc.add(signs);

            // ── 现场照片 ──
            List<String> photos;
            try {
                photos = r.photosJson == null ? List.of()
                        : JsonUtil.MAPPER.readValue(r.photosJson,
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                                });
            } catch (Exception e) {
                photos = List.of();
            }
            Paragraph photoTitle = new Paragraph("现场照片（" + photos.size() + " 张）", label);
            photoTitle.setSpacingBefore(10);
            photoTitle.setSpacingAfter(4);
            doc.add(photoTitle);
            if (photos.isEmpty()) {
                doc.add(new Paragraph("本次作业无现场照片。", small));
            } else {
                PdfPTable photoGrid = new PdfPTable(2);
                photoGrid.setWidthPercentage(100);
                for (String url : photos) {
                    photoGrid.addCell(photoCell(url, small));
                }
                if (photos.size() % 2 == 1) {
                    photoGrid.addCell(emptyCell());
                }
                doc.add(photoGrid);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("维保记录 PDF 生成失败: id={}, {}", r == null ? null : r.id, e.getMessage());
            throw new IllegalStateException("PDF 生成失败", e);
        }
    }

    /** 照片/签字按 URL 拉取；本机 /files/{id} 回退查 AppFile 本地路径；失败返回 null（占位文字） */
    protected byte[] loadImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            if (url.startsWith("http")) {
                if (!allowedHost(url)) {
                    log.warn("PDF 图片地址不在白名单内，已跳过抓取: {}", mask(url));
                    return null;
                }
                return restTemplate.getForObject(url, byte[].class);
            }
            if (url.startsWith("/files/")) {
                AppFile f = fileMapper.selectById(url.substring("/files/".length()));
                if (f != null) {
                    var path = fileStorage.localPath(f);
                    return java.nio.file.Files.readAllBytes(path);
                }
            }
        } catch (Exception e) {
            log.warn("PDF 图片拉取失败（占位处理）: url={}, {}", mask(url), e.getMessage());
        }
        return null;
    }

    private PdfPCell signCell(String caption, String url, Font font) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.setMinimumHeight(70);
        byte[] img = loadImage(url);
        if (img != null) {
            try {
                Image image = Image.getInstance(img);
                image.scaleToFit(120, 50);
                image.setAlignment(Element.ALIGN_CENTER);
                cell.addElement(image);
            } catch (Exception e) {
                cell.addElement(new Paragraph("（签字图片无法解析）", font));
            }
        } else {
            cell.addElement(new Paragraph(url == null || url.isBlank()
                    ? "（未签字）" : "（签字图片加载失败）", font));
        }
        cell.addElement(new Paragraph(caption, font));
        return cell;
    }

    private PdfPCell photoCell(String url, Font font) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.setMinimumHeight(110);
        byte[] img = loadImage(url);
        if (img != null) {
            try {
                Image image = Image.getInstance(img);
                image.scaleToFit(240, 180);
                image.setAlignment(Element.ALIGN_CENTER);
                cell.addElement(image);
            } catch (Exception e) {
                cell.addElement(new Paragraph("（图片无法解析）", font));
            }
        } else {
            cell.addElement(new Paragraph("（照片加载失败）", font));
        }
        cell.addElement(new Paragraph(mask(url), font));
        return cell;
    }

    private static PdfPTable table(int columns, Font label, Font value) {
        PdfPTable t = new PdfPTable(columns);
        t.setWidthPercentage(100);
        t.setSpacingAfter(6);
        return t;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(5);
        c.setBackgroundColor(new java.awt.Color(245, 245, 245));
        return c;
    }

    private static PdfPCell headCell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(4);
        c.setBackgroundColor(new java.awt.Color(230, 230, 230));
        return c;
    }

    private static PdfPCell bodyCell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(4);
        return c;
    }

    private static PdfPCell emptyCell() {
        return new PdfPCell(new Phrase(" "));
    }

    private static Font font(float size, int style) {
        try {
            BaseFont base = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            return new Font(base, size, style);
        } catch (Exception e) {
            throw new IllegalStateException("中文字体初始化失败", e);
        }
    }

    private static String mask(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        return base.length() > 60 ? base.substring(0, 60) + "…" : base;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
