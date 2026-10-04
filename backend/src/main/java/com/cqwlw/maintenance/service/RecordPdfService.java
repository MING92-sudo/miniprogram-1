package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
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

    public RecordPdfService(RestTemplate restTemplate, AppFileMapper fileMapper,
                            FileStorageService fileStorage) {
        this.restTemplate = restTemplate;
        this.fileMapper = fileMapper;
        this.fileStorage = fileStorage;
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

            // ── 单据头（样单 2026-10-05）：标题居左，编号/性质/状态居右 ──
            PdfPTable head = new PdfPTable(2);
            head.setWidthPercentage(100);
            head.setWidths(new int[] {1, 1});
            PdfPCell headL = new PdfPCell();
            headL.setBorder(PdfPCell.NO_BORDER);
            headL.addElement(new Paragraph("电梯维保单", title));
            headL.addElement(new Paragraph("（维保单位名称 / Logo 占位）", small));
            PdfPCell headR = new PdfPCell(new Paragraph(
                    "编号：" + nz(r.originalRecordId) + "\n维保性质：" + nz(r.workType)
                            + "\n单据状态：" + (r.checkoutTime == null ? "进行中" : "已完成"),
                    font(9, Font.BOLD, new java.awt.Color(214, 108, 24))));
            headR.setBorder(PdfPCell.NO_BORDER);
            headR.setHorizontalAlignment(Element.ALIGN_RIGHT);
            head.addCell(headL);
            head.addCell(headR);
            head.setSpacingAfter(8);
            doc.add(head);

            // ── 维保日期表 ──
            PdfPTable info = table(4, label, value);
            info.addCell(cell("维保日期", label));
            info.addCell(cell(fmt(r.checkinTime) + "（实际）", value));
            info.addCell(cell("上次维保", label));
            info.addCell(cell(el == null || el.lastMaintenanceAt == null
                    ? "—" : TimeUtil.format(el.lastMaintenanceAt), value));
            info.addCell(cell("下次应维保", label));
            info.addCell(cell(TimeUtil.formatDate(r.nextMaintenanceDate), value));
            info.addCell(cell("维保合同编号", label));
            info.addCell(cell("—", value));
            doc.add(info);

            // ── 电梯基本信息（扫码自动带出） ──
            doc.add(sectionBar("电梯基本信息（扫码自动带出）"));
            PdfPTable elev = table(4, label, value);
            elev.addCell(cell("注册代码/登记证号", label));
            elev.addCell(cell(el == null ? "" : nz(el.regCode), value));
            elev.addCell(cell("使用单位内编号", label));
            elev.addCell(cell(el == null ? "" : nz(el.insideNumber), value));
            elev.addCell(cell("使用单位", label));
            elev.addCell(cell(uu == null ? "" : nz(uu.unitName), value));
            elev.addCell(cell("使用地点", label));
            elev.addCell(cell(el == null ? "" : nz(el.location), value));
            elev.addCell(cell("电梯类型", label));
            elev.addCell(cell(el == null ? "" : nz(el.category), value));
            elev.addCell(cell("层站数 / 载重 / 速度", label));
            elev.addCell(cell(el == null ? "" : elevSpec(el), value));
            doc.add(elev);

            // ── 维保人员与作业时间 ──
            PdfPTable work = table(4, label, value);
            work.addCell(cell("维保人员 1（签字）", label));
            work.addCell(cell(nz(r.workerName), value));
            work.addCell(cell("维保人员 2（签字）", label));
            work.addCell(cell(nz(r.assistantName), value));
            work.addCell(cell("作业开始（签到）", label));
            work.addCell(cell(fmt(r.checkinTime) + "（定位+时间戳）", value));
            work.addCell(cell("作业结束（签退）", label));
            work.addCell(cell(fmt(r.checkoutTime), value));
            work.addCell(cell("安全防护确认", label));
            PdfPCell safety = new PdfPCell(new Phrase(safetyLine(r.safetyJson), value));
            safety.setColspan(3);
            safety.setPadding(5);
            work.addCell(safety);
            doc.add(work);

            // ── 维保项目检查表（TSG T5002-2017 附件 A–D） ──
            List<Map<String, Object>> items = r.itemsJson == null ? List.of() : JsonUtil.readList(r.itemsJson);
            doc.add(sectionBar("维保项目检查表（按 TSG T5002-2017 附件 A–D 自动带出）"));
            PdfPTable itemsTable = new PdfPTable(new float[]{1f, 1.6f, 5f, 5f, 2f, 4f});
            itemsTable.setWidthPercentage(100);
            itemsTable.addCell(headCell("序号", label));
            itemsTable.addCell(headCell("部位", label));
            itemsTable.addCell(headCell("维保项目（内容）", label));
            itemsTable.addCell(headCell("基本要求", label));
            itemsTable.addCell(headCell("结果", label));
            itemsTable.addCell(headCell("处理情况/备注", label));
            int seq = 1;
            for (Map<String, Object> item : items) {
                if (Boolean.TRUE.equals(item.get("notInThisRun"))) {
                    continue;
                }
                itemsTable.addCell(bodyCell(String.valueOf(seq++), small));
                itemsTable.addCell(bodyCell(partOf(str(item.get("itemCode"))), small));
                itemsTable.addCell(bodyCell(nz(str(item.get("name"))), small));
                itemsTable.addCell(bodyCell(nz(str(item.get("requirement"))), small));
                String res = str(item.get("result"));
                PdfPCell resultCell = bodyCell(RESULT_TEXT.getOrDefault(res, nz(res)), small);
                if ("ABNORMAL".equals(res)) {
                    resultCell.setBackgroundColor(new java.awt.Color(255, 243, 224));
                }
                itemsTable.addCell(resultCell);
                itemsTable.addCell(bodyCell(remarkOf(item), small));
            }
            if (seq == 1) {
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("无检查项明细", small));
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("—", small));
                itemsTable.addCell(bodyCell("—", small));
            }
            doc.add(itemsTable);
            doc.add(new Paragraph("注：检查结果三态——正常 / 异常 / 不适用；异常项必须填写处理情况并拍照。",
                    small));

            // ── 发现问题及处理 / 待办事项 / 签字确认 ──
            PdfPTable issue = table(4, label, value);
            issue.addCell(cell("发现问题及处理", label));
            issue.addCell(longTextCell(abnormalSummary(items), value, 3));
            issue.addCell(cell("待办事项", label));
            String todo = nz(r.todoDesc);
            issue.addCell(longTextCell(todo.isEmpty() ? "无待办，销项后归档" : todo, value, 3));
            doc.add(issue);

            PdfPTable signs = new PdfPTable(3);
            signs.setWidthPercentage(100);
            signs.addCell(signCell("维保人员 1 签字（" + nz(r.workerName) + "）",
                    r.workerSignatureUrl, small));
            signs.addCell(signCell("维保人员 2 签字（" + nz(r.assistantName) + "）",
                    r.assistantSignatureUrl, small));
            signs.addCell(signCell("使用单位安全管理人员签字", r.signatureUrl, small));
            doc.add(signs);

            Paragraph foot = new Paragraph("本记录归入电梯安全技术档案，至少保存 4 年 · 打印时间："
                    + TimeUtil.format(TimeUtil.now()), small);
            foot.setSpacingBefore(6);
            doc.add(foot);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("维保记录 PDF 生成失败: id={}, {}", r == null ? null : r.id, e.getMessage());
            throw new IllegalStateException("PDF 生成失败", e);
        }
    }

    /** 电梯规格组合：层站数 / 载重 / 速度 */
    private static String elevSpec(Elevator el) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(el.stationsDoors)) {
            sb.append(el.stationsDoors);
        }
        if (el.ratedLoad != null) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(el.ratedLoad).append(el.ratedLoadUnit == null ? "kg" : el.ratedLoadUnit);
        }
        if (el.ratedSpeed != null) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(el.ratedSpeed).append(el.ratedSpeedUnit == null ? "m/s" : el.ratedSpeedUnit);
        }
        return sb.toString();
    }

    /** 部位：TSG 附件表号 → 部位名（1机房 2轿厢 3层站层门 4底坑 5井道，其余 —） */
    private static String partOf(String itemCode) {
        if (itemCode == null || !itemCode.matches("[A-D]-\\d+-\\d+")) {
            return "—";
        }
        int tableNo = Integer.parseInt(itemCode.split("-")[1]);
        return switch (tableNo) {
            case 1 -> "机房";
            case 2 -> "轿厢";
            case 3 -> "层站·层门";
            case 4 -> "底坑";
            case 5 -> "井道";
            default -> "—";
        };
    }

    /** 处理情况/备注：异常描述优先，其次读数/不适用原因 */
    private static String remarkOf(Map<String, Object> item) {
        String remark = firstNonBlank(
                str(item.get("abnormalDesc")), str(item.get("valueText")),
                item.get("value") == null ? null : ("读数 " + item.get("value")
                        + (item.get("valueUnit") == null ? "" : item.get("valueUnit"))),
                str(item.get("skipReason")));
        return nz(remark);
    }

    /** 异常项汇总（发现问题及处理栏） */
    private static String abnormalSummary(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : items) {
            if (!"ABNORMAL".equals(str(item.get("result")))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(nz(str(item.get("name"))));
            if (notBlank(str(item.get("problemCode")))) {
                sb.append("（").append(str(item.get("problemCode"))).append("）");
            }
            sb.append("：").append(nz(str(item.get("abnormalDesc"))));
        }
        return sb.length() == 0 ? "无异常项。" : sb.toString();
    }

    /** 安全防护确认勾选行：safety_json 回显 ☑/☐（V20 采集） */
    private static String safetyLine(String safetyJson) {
        Map<String, Object> flags;
        try {
            flags = safetyJson == null || safetyJson.isBlank()
                    ? Map.of() : JsonUtil.MAPPER.readValue(safetyJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
        } catch (Exception e) {
            flags = Map.of();
        }
        boolean twoPerson = Boolean.TRUE.equals(flags.get("twoPerson"))
                || "true".equals(String.valueOf(flags.get("twoPerson")));
        return box(flags.get("warning")) + "警示标志　"
                + box(flags.get("barrier")) + "现场围拦　"
                + box(flags.get("powerOff")) + "断电挂牌（如需要）　"
                + (twoPerson ? "☑" : "☐") + "双人作业";
    }

    private static String box(Object flag) {
        return Boolean.parseBoolean(String.valueOf(flag)) ? "☑" : "☐";
    }

    /** 急修单 PDF（一梯一档，版式按用户样单 2026-10-05）：时间链/电梯基本信息/报修与处理记录/照片/签字确认 */
    public byte[] renderFault(Map<String, Object> v) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font title = font(16, Font.BOLD);
            Font label = font(9, Font.BOLD);
            Font value = font(9, Font.NORMAL);
            Font small = font(7.5f, Font.NORMAL);

            String status = str(v.get("status"));
            // ── 单据头：标题居左，编号/状态居右 ──
            PdfPTable head = new PdfPTable(2);
            head.setWidthPercentage(100);
            head.setWidths(new int[] {1, 1});
            PdfPCell headL = new PdfPCell(new Paragraph("电梯急修单", title));
            headL.setBorder(PdfPCell.NO_BORDER);
            PdfPCell headR = new PdfPCell(new Paragraph(
                    "编号：" + nz(str(v.get("faultNo"))) + "\n单据状态："
                            + ("CLOSED".equals(status) ? "已完成" : "未闭环") + "　·　页数：1/1",
                    font(9, Font.BOLD)));
            headR.setBorder(PdfPCell.NO_BORDER);
            headR.setHorizontalAlignment(Element.ALIGN_RIGHT);
            head.addCell(headL);
            head.addCell(headR);
            head.setSpacingAfter(8);
            doc.add(head);

            // ── 时间链表 ──
            PdfPTable info = table(4, label, value);
            info.addCell(cell("报修时间", label));
            info.addCell(cell(nz(str(v.get("createdAt"))), value));
            info.addCell(cell("报修人 / 电话", label));
            info.addCell(cell((nz(str(v.get("createdByName")))
                    + (nz(str(v.get("reporterPhone"))).isEmpty() ? "" : " / " + str(v.get("reporterPhone")))), value));
            info.addCell(cell("故障等级", label));
            info.addCell(cell(nz(str(v.get("faultType")).isEmpty() ? "一般故障" : str(v.get("faultType"))), value));
            info.addCell(cell("接单人员（维保员）", label));
            info.addCell(cell(nz(str(v.get("dispatchedWorkerName"))), value));
            info.addCell(cell("到场时间（签到）", label));
            info.addCell(cell(nz(str(v.get("arrivedAt"))), value));
            info.addCell(cell("维修结束时间", label));
            info.addCell(cell(nz(str(v.get("finishedAt"))), value));
            doc.add(info);

            // ── 电梯基本信息（扫码自动带出，不可手改） ──
            doc.add(sectionBar("电梯基本信息（扫码自动带出，不可手改）"));
            PdfPTable elev = table(4, label, value);
            elev.addCell(cell("注册代码/登记证号", label));
            elev.addCell(cell(nz(str(v.get("regCode"))), value));
            elev.addCell(cell("电梯类型", label));
            elev.addCell(cell(nz(str(v.get("model"))), value));
            elev.addCell(cell("使用单位", label));
            elev.addCell(cell(nz(str(v.get("useUnitName"))), value));
            elev.addCell(cell("设备地点", label));
            elev.addCell(cell(nz(str(v.get("location"))), value));
            elev.addCell(cell("层站数", label));
            elev.addCell(cell(nz(str(v.get("stationsDoors"))), value));
            elev.addCell(cell("额定载重 / 速度", label));
            elev.addCell(cell(nz(str(v.get("ratedSpec"))), value));
            elev.addCell(cell("维保单位 / 合同编号", label));
            PdfPCell maintCell = new PdfPCell(new Phrase(
                    nz(str(v.get("companyName"))) + " / —", value));
            maintCell.setPadding(5);
            maintCell.setColspan(3);
            elev.addCell(maintCell);
            doc.add(elev);

            // ── 报修与处理记录 ──
            doc.add(sectionBar("报修与处理记录"));
            PdfPTable record = table(4, label, value);
            record.addCell(cell("故障描述", label));
            record.addCell(longTextCell(nz(str(v.get("desc"))), value, 3));
            record.addCell(cell("现场情况描述", label));
            record.addCell(longTextCell(nz(str(v.get("siteDesc"))), value, 3));
            record.addCell(cell("处理结果", label));
            record.addCell(longTextCell(nz(str(v.get("result"))), value, 3));
            record.addCell(cell("待办事项", label));
            String todo = nz(str(v.get("todoDesc")));
            record.addCell(longTextCell(todo.isEmpty() ? "无待办，销项后归档" : todo, value, 3));
            doc.add(record);

            List<?> photos = v.get("photos") instanceof List<?> l ? l : List.of();
            doc.add(sectionBar("故障点位置照片（" + photos.size() + " 张，建议含时间水印）"));
            if (photos.isEmpty()) {
                doc.add(new Paragraph("无现场照片。", small));
            } else {
                PdfPTable photoGrid = new PdfPTable(2);
                photoGrid.setWidthPercentage(100);
                for (Object url : photos) {
                    photoGrid.addCell(photoCell(String.valueOf(url), small));
                }
                if (photos.size() % 2 == 1) {
                    photoGrid.addCell(emptyCell());
                }
                doc.add(photoGrid);
            }

            // ── 签字确认（签名图片自动落入对应位置） ──
            PdfPTable sign = new PdfPTable(4);
            sign.setWidthPercentage(100);
            sign.setWidths(new int[] {1, 2, 1, 1});
            sign.addCell(cell("签字确认", label));
            PdfPCell signMain = new PdfPCell();
            signMain.setColspan(3);
            signMain.setPadding(6);
            byte[] signImg = loadImage(str(v.get("signature")));
            if (signImg != null) {
                try {
                    Image img = Image.getInstance(signImg);
                    img.scaleToFit(120, 40);
                    img.setAlignment(Element.ALIGN_LEFT);
                    signMain.addElement(img);
                } catch (Exception e) {
                    signMain.addElement(new Paragraph("（签字图片无法解析）", small));
                }
            } else {
                signMain.addElement(new Paragraph(
                        nz(str(v.get("signature"))).isEmpty() ? "使用单位安全管理员签字：＿＿＿＿＿＿" : "（签字图片加载失败）",
                        small));
            }
            signMain.addElement(new Paragraph("日期：" + (nz(str(v.get("confirmedAt"))).isEmpty() ? "＿＿＿＿＿＿" : str(v.get("confirmedAt"))), small));
            signMain.addElement(new Paragraph("（可选）维保员签字：＿＿＿＿＿＿　维保单位（盖章）：＿＿＿＿＿＿", small));
            sign.addCell(signMain);
            doc.add(sign);

            Paragraph foot = new Paragraph("本单据由系统生成，签字确认后锁定归档 · 打印时间："
                    + TimeUtil.format(TimeUtil.now()) + " · 本单共 1 页", small);
            foot.setSpacingBefore(6);
            doc.add(foot);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("急修单 PDF 生成失败: id={}, {}", v == null ? null : v.get("id"), e.getMessage());
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
                // 同源 /files/{id}：直接读存储（本地或 COS），避免自调用 HTTP（2026-10-05）
                if (url.contains("/files/")) {
                    return readStoredFile(url.substring(url.lastIndexOf("/files/") + "/files/".length()));
                }
                byte[] remote = restTemplate.getForObject(url, byte[].class);
                if (remote != null) {
                    return remote;
                }
                // 历史数据：COS 直链私有读不可达时，按 url 反查文件记录走内网读取
                AppFile legacy = fileMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppFile>()
                                .eq(AppFile::getUrl, url)).stream()
                        .findFirst().orElse(null);
                return legacy == null ? null : fileStorage.readBytes(legacy);
            }
            if (url.startsWith("/files/")) {
                return readStoredFile(url.substring("/files/".length()));
            }
        } catch (Exception e) {
            log.warn("PDF 图片拉取失败（占位处理）: url={}, {}", mask(url), e.getMessage());
        }
        return null;
    }

    private byte[] readStoredFile(String fileId) {
        AppFile f = fileMapper.selectById(fileId);
        return f == null ? null : fileStorage.readBytes(f);
    }

    /** 深色分节标题条（样单：电梯基本信息 / 报修与处理记录 / 故障点位置照片），返回单行表供 doc.add */
    private static PdfPTable sectionBar(String text) {
        Font barFont = font(9.5f, Font.BOLD, java.awt.Color.WHITE);
        PdfPCell c = new PdfPCell(new Phrase(text, barFont));
        c.setBackgroundColor(new java.awt.Color(23, 50, 77));
        c.setPadding(5);
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(100);
        wrap.setSpacingBefore(8);
        wrap.setSpacingAfter(0);
        wrap.addCell(c);
        return wrap;
    }

    /** 跨列长文本单元格（故障描述/现场情况/处理结果/待办事项） */
    private static PdfPCell longTextCell(String text, Font font, int colspan) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(5);
        c.setColspan(colspan);
        c.setMinimumHeight(24);
        return c;
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

    private static Font font(float size, int style, java.awt.Color color) {
        Font f = font(size, style);
        f.setColor(color);
        return f;
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
