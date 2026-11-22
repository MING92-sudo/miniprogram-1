package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecordPdfService {

    private static final Logger log = LoggerFactory.getLogger(RecordPdfService.class);

    private static final Map<String, String> RESULT_MARK =
            Map.of("NORMAL", "√", "ABNORMAL", "×", "NA", "—");
    /** 周期短标签：快照 item.freq → 表内「周期」列 */
    private static final Map<String, String> FREQ_SHORT =
            Map.of("HALF", "半月", "QUARTER", "季度", "HALF_YEAR", "半年", "YEAR", "年度");
    /** 未采集字段的统一占位（第七条第(一)项要求项，系统暂无对应列） */
    private static final String NOT_COLLECTED = "—（档案未采集）";
    private static final java.awt.Color LABEL_BG = new java.awt.Color(242, 242, 242);

    private final RestTemplate restTemplate;
    private final AppFileMapper fileMapper;
    private final FileStorageService fileStorage;
    private final CompanyMapper companyMapper;

    /** 可选依赖：签字回退按来源工单解析（record.work_order_id 为空的旧数据自动跳过） */
    @Autowired(required = false)
    private WorkOrderMapper workOrderMapper;

    public RecordPdfService(RestTemplate restTemplate, AppFileMapper fileMapper,
                            FileStorageService fileStorage, CompanyMapper companyMapper) {
        this.restTemplate = restTemplate;
        this.fileMapper = fileMapper;
        this.fileStorage = fileStorage;
        this.companyMapper = companyMapper;
    }

    // ─────────────────────────── 表单 1：维保记录 ───────────────────────────

    public byte[] render(MaintainRecord r, Elevator el, UseUnit uu) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Fonts f = new Fonts();
            Document doc = new Document(PageSize.A4, 51, 51, 42, 46);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            String recordNo = orDash(nz(r.originalRecordId));
            writer.setPageEvent(new FormFooter(f.small,
                    "本记录归入电梯安全技术档案，至少保存 4 年（TSG T5002—2017 第五条第(六)项）",
                    "记录编号：" + recordNo + "　·　打印时间：" + TimeUtil.format(TimeUtil.now())));
            doc.open();

            List<Map<String, Object>> items = r.itemsJson == null ? List.of() : JsonUtil.readList(r.itemsJson);
            if (items == null) {
                items = List.of();
            }
            List<Map<String, Object>> executed = new ArrayList<>();
            for (Map<String, Object> item : items) {
                if (!Boolean.TRUE.equals(item.get("notInThisRun"))) {
                    executed.add(item);
                }
            }

            doc.add(title("电梯维护保养记录", f));
            doc.add(subtitleRow("记录编号：" + recordNo,
                    rangeLabel(appendixOf(el == null ? null : el.category), workTypeCodeOf(r)), f));
            backfillOrderSignatures(r);

            doc.add(kvTable("一、使用单位与设备基本情况", deviceRows(el, uu, r.useUnitName), f));
            doc.add(kvTable("二、维保作业信息", workRows(r, executed.size(), items.size()), f));
            doc.add(itemsTable(executed, f));
            Paragraph legend = new Paragraph(
                    "注：√=符合要求；×=不符合要求（须在本行「处理情况」栏记载处理措施并拍照留证）；"
                            + "—=不适用（须注明原因）。「周期」栏标注该条目所属维保频次（半月/季度/半年/年度）。",
                    f.small);
            legend.setSpacingBefore(4);
            legend.setSpacingAfter(2);
            doc.add(legend);

            doc.add(kvTable("四、发现问题及处理", issueRows(executed, r), f));
            doc.add(kvTable("五、现场安全防护确认",
                    List.<String[]>of(new String[]{"落实情况", safetyLine(r.safetyJson)}), f));
            List<String> photos = recordPhotoUrls(r, items);
            doc.add(photoSection("六、现场照片留证（" + photos.size() + " 张）", photos, f));
            doc.add(signTable(r, f));

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("维保记录 PDF 生成失败: id={}, {}", r == null ? null : r.id, e.getMessage());
            throw new IllegalStateException("PDF 生成失败", e);
        }
    }

    /** 一、使用单位与设备基本情况（第七条(一)(二)、第八条）：设备档案 + 品种追加技术参数行 */
    private List<String[]> deviceRows(Elevator el, UseUnit uu, String snapshotUnitName) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("使用单位", unitNameForSnapshot(snapshotUnitName, uu));
        v.put("使用单位内编号", el == null ? "" : nz(el.insideNumber));
        v.put("使用地点", el == null ? "" : nz(el.location));
        v.put("项目名称（小区）", el == null ? "" : nz(el.projectName));
        v.put("电梯品种（型式）", el == null ? "" : nz(el.category));
        v.put("特殊类别（消防/防爆）", el == null ? "" : nz(el.specialType));
        v.put("出厂编号", el == null ? "" : nz(el.factoryNumber));
        v.put("设备代码", el == null ? "" : nz(el.deviceCode));
        v.put("注册代码/登记证号", el == null ? "" : nz(el.regCode));
        v.put("电梯型号", el == null ? "" : nz(el.model));
        v.put("制造单位", el == null ? "" : nz(el.manufacturer));
        v.put("安装/改造/重大修理单位", NOT_COLLECTED);
        v.put("驱动方式", el == null ? "" : nz(el.driveMode));
        v.put("额定载重量", el == null || el.ratedLoad == null ? ""
                : el.ratedLoad + unitOf(el.ratedLoadUnit, "kg"));
        v.put("额定速度", el == null || el.ratedSpeed == null ? ""
                : el.ratedSpeed.stripTrailingZeros().toPlainString() + unitOf(el.ratedSpeedUnit, "m/s"));
        v.put("层站门数", el == null ? "" : nz(el.stationsDoors));
        return layoutDeviceRows(v, el == null ? null : el.category);
    }

    /** 急修单的一、区块：设备信息来自 /faults、/admin/faults 视图（DirectoryService.enrichFaultViews） */
    private List<String[]> deviceRowsOfMap(Map<String, Object> v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("使用单位", str(v.get("useUnitName")));
        m.put("使用单位内编号", str(v.get("insideNumber")));
        m.put("使用地点", str(v.get("location")));
        m.put("项目名称（小区）", str(v.get("projectName")));
        m.put("电梯品种（型式）", str(v.get("category")));
        m.put("特殊类别（消防/防爆）", str(v.get("specialType")));
        m.put("出厂编号", str(v.get("factoryNumber")));
        m.put("设备代码", str(v.get("deviceCode")));
        m.put("注册代码/登记证号", str(v.get("regCode")));
        m.put("电梯型号", str(v.get("model")));
        m.put("制造单位", str(v.get("manufacturer")));
        m.put("安装/改造/重大修理单位", NOT_COLLECTED);
        m.put("驱动方式", str(v.get("driveMode")));
        m.put("额定载重量", str(v.get("ratedLoadSpec")));
        m.put("额定速度", str(v.get("ratedSpeedSpec")));
        m.put("层站门数", str(v.get("stationsDoors")));
        return layoutDeviceRows(m, str(v.get("category")));
    }

    /** 设备字段固定 8 行 × 2 列排布；第八条按品种追加参数行（未采集字段打印明确占位） */
    private List<String[]> layoutDeviceRows(Map<String, String> v, String category) {
        String[][] fields = {
                {"使用单位", "使用单位内编号"},
                {"使用地点", "项目名称（小区）"},
                {"电梯品种（型式）", "特殊类别（消防/防爆）"},
                {"产品编号", "设备代码"},
                {"注册代码/登记证号", "电梯型号"},
                {"制造单位", "安装/改造/重大修理单位"},
                {"驱动方式", "额定载重量"},
                {"额定速度", "层站门数"}
        };
        List<String[]> rows = new ArrayList<>();
        for (String[] pair : fields) {
            rows.add(new String[]{pair[0], orDash(v.get(pair[0])), pair[1], orDash(v.get(pair[1]))});
        }
        String appendix = appendixOf(category);
        if ("B".equals(appendix)) {
            rows.add(new String[]{"油缸数量", NOT_COLLECTED, "顶升型式", NOT_COLLECTED});
        }
        if ("D".equals(appendix)) {
            rows.add(new String[]{"倾斜角", NOT_COLLECTED, "名义速度", NOT_COLLECTED});
            rows.add(new String[]{"提升高度", NOT_COLLECTED, "名义宽度", NOT_COLLECTED});
            rows.add(new String[]{"主机功率", NOT_COLLECTED, "使用区段长度", NOT_COLLECTED});
        }
        return rows;
    }

    /** 二、维保作业信息（第七条(三)） */
    private List<String[]> workRows(MaintainRecord r, int executed, int total) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"维保单位", nz(companyNameOf(r)), "维保合同编号", "—"});
        rows.add(new String[]{"维保类别", nz(r.workType) + "（" + workTypeCodeOf(r) + "）",
                "维保日期", r.checkinTime == null ? "—" : TimeUtil.date(r.checkinTime)});
        rows.add(new String[]{"维保人员（主）", orDash(nz(r.workerName)),
                "配合人员", orDash(nz(r.assistantName))});
        rows.add(new String[]{"作业开始（签到）", orDash(fmt(r.checkinTime)),
                "作业结束（签退）", orDash(fmt(r.checkoutTime))});
        rows.add(new String[]{"作业时长", orDash(nz(r.duration)),
                "本次执行项数", "执行 " + executed + " 项 / 应执行 " + total + " 项"});
        return rows;
    }

    /** 三、维护保养项目（内容）和要求：六列，分页重复「区块标题 + 列表头」两行 */
    private PdfPTable itemsTable(List<Map<String, Object>> executed, Fonts f) {
        PdfPTable t = new PdfPTable(new float[]{6, 7, 26, 27, 8, 26});
        t.setWidthPercentage(100);
        t.setHeaderRows(2);
        t.setSpacingBefore(6);
        PdfPCell section = new PdfPCell(new Phrase("三、维护保养项目（内容）和要求", f.section));
        section.setColspan(6);
        section.setBackgroundColor(LABEL_BG);
        section.setPadding(4);
        t.addCell(section);
        for (String head : new String[]{"序号", "周期", "维护保养项目（内容）", "维护保养基本要求",
                "结果", "处理情况（调整、更换易损件等详细记载）"}) {
            t.addCell(headCell(head, f));
        }
        int seq = 1;
        for (Map<String, Object> item : executed) {
            t.addCell(bodyCell(String.valueOf(seq++), f, Element.ALIGN_CENTER));
            t.addCell(bodyCell(FREQ_SHORT.getOrDefault(nz(str(item.get("freq"))), "—"),
                    f, Element.ALIGN_CENTER));
            t.addCell(bodyCell(nz(str(item.get("name"))), f, Element.ALIGN_LEFT));
            t.addCell(bodyCell(nz(str(item.get("requirement"))), f, Element.ALIGN_LEFT));
            t.addCell(bodyCell(RESULT_MARK.getOrDefault(nz(str(item.get("result"))), "—"),
                    f, Element.ALIGN_CENTER));
            t.addCell(bodyCell(remarkOf(item), f, Element.ALIGN_LEFT));
        }
        if (seq == 1) {
            PdfPCell empty = new PdfPCell(new Phrase("无检查项明细", f.body));
            empty.setColspan(6);
            empty.setPadding(6);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(empty);
        }
        return t;
    }

    /** 四、发现问题及处理（第七条(四)：调整、更换易损件的详细记载） */
    private List<String[]> issueRows(List<Map<String, Object>> executed, MaintainRecord r) {
        int abnormal = 0;
        for (Map<String, Object> item : executed) {
            if ("ABNORMAL".equals(str(item.get("result")))) {
                abnormal++;
            }
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"异常项目及处理", abnormalSummary(executed)});
        rows.add(new String[]{"调整、更换易损件",
                abnormal == 0 ? "—（本次无）" : "详见上栏「异常项目及处理」记载"});
        String todo = nz(r.todoDesc);
        rows.add(new String[]{"待办事项", todo.isEmpty() ? "无待办，销项后归档" : todo});
        rows.add(new String[]{"维保结论", abnormal == 0
                ? "■ 符合要求　　□ 存在不符合项，须整改"
                : "□ 符合要求　　■ 存在不符合项，须整改（不符合 " + abnormal + " 项）"});
        return rows;
    }

    /** 七、签字确认（第七条末段：使用单位安全管理人员签字确认） */
    private PdfPTable signTable(MaintainRecord r, Fonts f) {
        PdfPTable t = new PdfPTable(new float[]{1, 1, 1.15f, 0.85f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        PdfPCell section = new PdfPCell(new Phrase("七、签字确认", f.section));
        section.setColspan(4);
        section.setBackgroundColor(LABEL_BG);
        section.setPadding(4);
        t.addCell(section);
        String checkoutDate = r.checkoutTime == null ? "＿＿＿＿年＿＿月＿＿日"
                : TimeUtil.date(r.checkoutTime);
        t.addCell(signBlock(signCaption("维保人员 1", r.workerName), r.workerSignatureUrl,
                "签署日期：" + checkoutDate, f));
        t.addCell(signBlock(signCaption("维保人员 2", r.assistantName), r.assistantSignatureUrl,
                "签署日期：" + checkoutDate, f));
        t.addCell(signBlock("使用单位安全管理人员（签字）", r.signatureUrl,
                "签署日期：＿＿＿＿年＿＿月＿＿日", f));
        t.addCell(signBlock("维保单位（盖章）", null, "", f));
        return t;
    }

    // ─────────────────────────── 表单 2：急修记录 ───────────────────────────

    public byte[] renderFault(Map<String, Object> v) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Fonts f = new Fonts();
            Document doc = new Document(PageSize.A4, 51, 51, 42, 46);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            String faultNo = orDash(str(v.get("faultNo")));
            writer.setPageEvent(new FormFooter(f.small,
                    "本单据随电梯安全技术档案保存，至少保存 4 年（TSG T5002—2017 第五条第(五)(六)项）",
                    "单号：" + faultNo + "　·　打印时间：" + TimeUtil.format(TimeUtil.now())));
            doc.open();

            doc.add(title("电梯急修记录单", f));
            doc.add(subtitle("单号：" + faultNo + "　·　状态：" + faultStatusText(str(v.get("status")))
                    + "　·　登记时间：" + orDash(str(v.get("createdAt"))), f));

            doc.add(kvTable("一、使用单位与设备基本情况", deviceRowsOfMap(v), f));
            doc.add(kvTable("二、报修与响应", responseRows(v), f));
            doc.add(kvTable("三、故障情况与处理", faultRows(v), f));

            List<String> photos = new ArrayList<>();
            for (Object url : v.get("photos") instanceof List<?> l ? l : List.of()) {
                photos.add(String.valueOf(url));
            }
            doc.add(photoSection("四、现场照片（" + photos.size() + " 张）", photos, f));
            doc.add(faultSignTable(v, f));

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("急修单 PDF 生成失败: id={}, {}", v == null ? null : v.get("id"), e.getMessage());
            throw new IllegalStateException("PDF 生成失败", e);
        }
    }

    /** 二、报修与响应（第五条(四)：到场时限；本表只呈现事实与法定期限选项，不自动判合规） */
    private List<String[]> responseRows(Map<String, Object> v) {
        String arrived = nz(str(v.get("arrivedAt")));
        String created = nz(str(v.get("createdAt")));
        String reporter = nz(str(v.get("createdByName")));
        if (reporter.isEmpty()) {
            reporter = nz(str(v.get("createdBy")));
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"报修时间", orDash(created), "报修人", orDash(reporter)});
        rows.add(new String[]{"联系电话", orDash(str(v.get("reporterPhone"))), "故障等级", faultTypeText(v)});
        rows.add(new String[]{"派单时间", orDash(str(v.get("dispatchedAt"))), "接单维保员",
                orDash(str(v.get("dispatchedWorkerName")))});
        rows.add(new String[]{"到场时间", orDash(arrived), "响应时长", responseCost(created, arrived)});
        rows.add(new String[]{"响应时限",
                "□ ≤30 分钟（直辖市或设区的市）　　□ ≤60 分钟（其他地区）　　实际响应："
                        + responseCost(created, arrived)});
        return rows;
    }

    /** 三、故障情况与处理（第五条(五)：故障情况及时详细记录） */
    private List<String[]> faultRows(Map<String, Object> v) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"故障现象", orDash(str(v.get("desc")))});
        rows.add(new String[]{"现场检查情况", orDash(str(v.get("siteDesc")))});
        rows.add(new String[]{"故障原因分析", "—（系统未单独采集，见上栏「现场检查情况」）"});
        rows.add(new String[]{"处理措施（含更换零部件）", orDash(str(v.get("result")))});
        rows.add(new String[]{"恢复运行时间", orDash(str(v.get("finishedAt")))});
        String todo = nz(str(v.get("todoDesc")));
        rows.add(new String[]{"待办事项", todo.isEmpty() ? "无待办，销项后归档" : todo});
        return rows;
    }

    /** 五、确认签字：维保人员手签 + 使用单位安全管理人员签字图 + 盖章位 */
    private PdfPTable faultSignTable(Map<String, Object> v, Fonts f) {
        PdfPTable t = new PdfPTable(new float[]{1, 1.2f, 0.9f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        PdfPCell section = new PdfPCell(new Phrase("五、确认签字", f.section));
        section.setColspan(3);
        section.setBackgroundColor(LABEL_BG);
        section.setPadding(4);
        t.addCell(section);
        t.addCell(signBlock(signCaption("维保人员", str(v.get("dispatchedWorkerName"))),
                null, "签署日期：＿＿＿＿年＿＿月＿＿日", f));
        String confirmed = nz(str(v.get("confirmedAt")));
        t.addCell(signBlock("使用单位安全管理人员（签字）", str(v.get("signature")),
                "签署日期：" + (confirmed.isEmpty() ? "＿＿＿＿年＿＿月＿＿日" : confirmed), f));
        t.addCell(signBlock("维保单位（盖章）", null, "", f));
        return t;
    }

    // ────────────────────────────── 通用版式 ──────────────────────────────

    /** 表单标题（16pt 粗，居中） */
    private static Paragraph title(String text, Fonts f) {
        Paragraph p = new Paragraph(text, f.title);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(2);
        return p;
    }

    /** 副标题：记录编号 / 附件表号 / 单号状态（8.5pt，居中） */
    private static Paragraph subtitle(String text, Fonts f) {
        Paragraph p = new Paragraph(text, f.body);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(8);
        return p;
    }

    private static PdfPTable subtitleRow(String left, String right, Fonts f) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        PdfPCell l = new PdfPCell(new Phrase(left, f.body));
        l.setBorder(Rectangle.NO_BORDER);
        l.setHorizontalAlignment(Element.ALIGN_LEFT);
        l.setPaddingLeft(0);
        l.setPaddingBottom(6);
        PdfPCell r = new PdfPCell(new Phrase(right, f.body));
        r.setBorder(Rectangle.NO_BORDER);
        r.setHorizontalAlignment(Element.ALIGN_RIGHT);
        r.setPaddingRight(0);
        r.setPaddingBottom(6);
        t.addCell(l);
        t.addCell(r);
        t.setSpacingAfter(6);
        return t;
    }

    /** 仅内存回填用于导出渲染，不落库）。 */
    private void backfillOrderSignatures(MaintainRecord r) {
        if (workOrderMapper == null || r == null || nz(r.workOrderId).isBlank()) {
            return;
        }
        boolean needWorker = nz(r.workerSignatureUrl).isBlank();
        boolean needAssistant = nz(r.assistantSignatureUrl).isBlank();
        if (!needWorker && !needAssistant) {
            return;
        }
        WorkOrder o = workOrderMapper.selectById(r.workOrderId);
        if (o == null) {
            return;
        }
        if (needWorker) {
            String u = fileStorage.urlOf(nz(o.workerSignatureFileId));
            if (u != null && !u.isEmpty()) {
                r.workerSignatureUrl = u;
            }
        }
        if (needAssistant) {
            String u = fileStorage.urlOf(nz(o.assistantSignatureFileId));
            if (u != null && !u.isEmpty()) {
                r.assistantSignatureUrl = u;
            }
        }
    }

    private PdfPTable kvTable(String sectionTitle, List<String[]> rows, Fonts f) {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{1.38f, 2.15f, 1.38f, 1.85f});
        t.setSpacingBefore(6);
        if (sectionTitle != null) {
            PdfPCell section = new PdfPCell(new Phrase(sectionTitle, f.section));
            section.setColspan(4);
            section.setBackgroundColor(LABEL_BG);
            section.setPadding(4);
            t.addCell(section);
        }
        for (String[] row : rows) {
            t.addCell(labelCell(row[0], f));
            if (row.length == 4) {
                t.addCell(valueCell(orDash(row[1]), f, 1));
                t.addCell(labelCell(row[2], f));
                t.addCell(valueCell(orDash(row[3]), f, 1));
            } else {
                t.addCell(valueCell(orDash(row[1]), f, 3));
            }
        }
        return t;
    }

    /** 照片区：两列网格，格子数随照片数量自适应（奇数张末格跨两列） */
    private PdfPTable photoSection(String sectionTitle, List<String> urls, Fonts f) {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        PdfPCell section = new PdfPCell(new Phrase(sectionTitle, f.section));
        section.setColspan(2);
        section.setBackgroundColor(LABEL_BG);
        section.setPadding(4);
        t.addCell(section);
        if (urls.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("无现场照片", f.small));
            empty.setColspan(2);
            empty.setPadding(6);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(empty);
            return t;
        }
        for (int i = 0; i < urls.size(); i++) {
            PdfPCell cell = photoCell(urls.get(i), "照片 " + (i + 1), f.small);
            if (i == urls.size() - 1 && urls.size() % 2 == 1) {
                cell.setColspan(2);
            }
            t.addCell(cell);
        }
        return t;
    }

    /** 签字格：标题 + 签字图片（缺失打印占位）+ 签署日期行 */
    private PdfPCell signBlock(String caption, String url, String dateLine, Fonts f) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(5);
        cell.setMinimumHeight(78);
        cell.addElement(new Paragraph(caption, f.label));
        byte[] img = loadImage(url);
        if (img != null) {
            try {
                Image image = Image.getInstance(img);
                image.scaleToFit(120, 42);
                image.setAlignment(Element.ALIGN_CENTER);
                cell.addElement(image);
            } catch (Exception e) {
                cell.addElement(new Paragraph("（签字图片无法解析）", f.small));
            }
        } else if (url != null && !url.isBlank()) {
            cell.addElement(new Paragraph("（签字图片加载失败）", f.small));
        } else {
            cell.addElement(new Paragraph("＿＿＿＿＿＿＿＿", f.small));
        }
        if (dateLine != null && !dateLine.isEmpty()) {
            cell.addElement(new Paragraph(dateLine, f.small));
        }
        return cell;
    }

    private static PdfPCell labelCell(String text, Fonts f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f.label));
        c.setPadding(4);
        c.setBackgroundColor(LABEL_BG);
        return c;
    }

    private static PdfPCell valueCell(String text, Fonts f, int colspan) {
        PdfPCell c = new PdfPCell(new Phrase(text, f.body));
        c.setPadding(4);
        c.setMinimumHeight(16);
        if (colspan > 1) {
            c.setColspan(colspan);
        }
        return c;
    }

    private static PdfPCell headCell(String text, Fonts f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f.head));
        c.setPadding(4);
        c.setBackgroundColor(LABEL_BG);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private static PdfPCell bodyCell(String text, Fonts f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f.body));
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        return c;
    }

    static String unitNameForSnapshot(String snapshotUnitName, UseUnit uu) {
        if (snapshotUnitName != null && !snapshotUnitName.isBlank()) {
            return snapshotUnitName.trim();
        }
        return "归属待核对";
    }

    static String appendixOf(String category) {
        if (category == null) {
            return "A";
        }
        if (category.contains("液压")) {
            return "B";
        }
        if (category.contains("杂物")) {
            return "C";
        }
        if (category.contains("自动扶梯") || category.contains("人行道")) {
            return "D";
        }
        return "A";
    }

    /** 维保类别码：优先 workTypeCode，历史记录缺码时按名称推断（HM/TM/SM/OY/FM） */
    static String workTypeCodeOf(MaintainRecord r) {
        if (r == null) {
            return "HM";
        }
        if (r.workTypeCode != null && !r.workTypeCode.isBlank()) {
            return r.workTypeCode;
        }
        String t = nz(r.workType);
        if (t.contains("季度")) {
            return "TM";
        }
        if (t.contains("半年")) {
            return "SM";
        }
        if (t.contains("年度")) {
            return "OY";
        }
        if (t.contains("按需")) {
            return "FM";
        }
        return "HM";
    }

    /** 副标题的表号范围：附件A 表A-1～表A-2（半月、季度）——附件 A—D 为"还应当符合下表"的累加式 */
    static String rangeLabel(String appendix, String workTypeCode) {
        switch (workTypeCode) {
            case "TM":
                return "附件" + appendix + " 表" + appendix + "-1～表" + appendix + "-2（半月、季度）";
            case "SM":
                return "附件" + appendix + " 表" + appendix + "-1～表" + appendix + "-3（半月、季度、半年）";
            case "OY":
                return "附件" + appendix + " 表" + appendix + "-1～表" + appendix + "-4"
                        + "（半月、季度、半年、年度）";
            case "FM":
                return "按需维保（依合同约定项目，参照表" + appendix + "-1）";
            default:
                return "附件" + appendix + " 表" + appendix + "-1（半月）";
        }
    }

    /** 急修单状态文案（OPEN 处理中 / ASSIGNED 已派单 / CLOSED 已闭环） */
    private static String faultStatusText(String status) {
        if ("CLOSED".equals(status)) {
            return "已闭环";
        }
        if ("ASSIGNED".equals(status)) {
            return "已派单";
        }
        return "处理中";
    }

    /** 故障等级（faultType）：空值按一般故障 */
    private static String faultTypeText(Map<String, Object> v) {
        String type = nz(str(v.get("faultType")));
        return type.isEmpty() ? "一般故障" : type;
    }

    /** 响应时长：报修→到场分钟数；到场时间缺失时打印占位（不编造） */
    private static String responseCost(String createdAt, String arrivedAt) {
        if (arrivedAt.isEmpty()) {
            return "—（未记录到场时间）";
        }
        long minutes = TimeUtil.minutesBetween(createdAt, arrivedAt);
        return minutes < 0 ? "—" : minutes + " 分钟";
    }

    /** 处理情况/备注：异常描述优先，其次不适用原因、读数（第七条(四)记载要求） */
    private static String remarkOf(Map<String, Object> item) {
        String result = nz(str(item.get("result")));
        if ("NA".equals(result)) {
            String reason = nz(str(item.get("skipReason")));
            return reason.isEmpty() ? "不适用" : "不适用：" + reason;
        }
        String remark = firstNonBlank(
                str(item.get("abnormalDesc")), str(item.get("valueText")),
                item.get("value") == null ? null : ("实测 " + item.get("value") + nz(str(item.get("valueUnit")))));
        return nz(remark);
    }

    /** 异常项汇总（四、区块「异常项目及处理」） */
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
        return sb.length() == 0 ? "无异常项" : sb.toString();
    }

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
        // 存量数据里有把缺省值写成字符串 "null" 的记录（JsonUtil.write(null)），Jackson 对字面量 null 会正常返回 null 而非抛错，必须再兜一层，否则整份 PDF 生成失败
        if (flags == null) {
            flags = Map.of();
        }
        boolean twoPerson = Boolean.TRUE.equals(flags.get("twoPerson"))
                || "true".equals(String.valueOf(flags.get("twoPerson")));
        return box(flags.get("warning")) + "警示标志　"
                + box(flags.get("barrier")) + "现场围拦　"
                + box(flags.get("powerOff")) + "断电挂牌（如需要）　"
                + (twoPerson ? "■" : "□") + "双人作业";
    }

    private static String box(Object flag) {
        return Boolean.parseBoolean(String.valueOf(flag)) ? "■" : "□";
    }

    /** 本地临时路径跨设备不可读，必须过滤，避免 PDF 中打印成"照片加载失败"。 */
    List<String> recordPhotoUrls(MaintainRecord r, List<Map<String, Object>> items) {
        List<String> resolved = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<String> itemUrls = new ArrayList<>();
            for (Object id : WorkOrderService.asList(item.get("photoFileIds"))) {
                String url = fileStorage.urlOf(String.valueOf(id));
                if (WorkOrderService.isHttpUrl(url)) {
                    itemUrls.add(url);
                }
            }
            if (itemUrls.isEmpty()) {
                for (Object photo : WorkOrderService.asList(item.get("photos"))) {
                    String url = fileStorage.resolveStoredUrl(String.valueOf(photo));
                    if (WorkOrderService.isHttpUrl(url)) {
                        itemUrls.add(url);
                    }
                }
            }
            resolved.addAll(itemUrls);
        }
        if (!resolved.isEmpty()) {
            return resolved;
        }
        for (String photo : JsonUtil.readStringList(r.photosJson)) {
            String url = fileStorage.resolveStoredUrl(photo);
            if (WorkOrderService.isHttpUrl(url)) {
                resolved.add(url);
            }
        }
        return resolved;
    }

    /** 照片/签字按 URL 拉取；本机 /files/{id} 回退查 AppFile 本地路径；失败返回 null（占位文字） */
    protected byte[] loadImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            if (url.startsWith("http")) {
                // 链接可能带 ?s= 访问签名，必须剥离后再取主键
                String sharedId = FileStorageService.fileIdFromUrl(url);
                if (sharedId != null) {
                    return readStoredFile(sharedId);
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
            String relativeId = FileStorageService.fileIdFromUrl(url);
            if (relativeId != null) {
                return readStoredFile(relativeId);
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

    private PdfPCell photoCell(String url, String caption, Font font) {
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
        cell.addElement(new Paragraph(caption, font));
        return cell;
    }

    // ────────────────────────────── 字体与工具 ──────────────────────────────

    private static final class Fonts {
        final Font title = font(16, Font.BOLD);
        final Font section = font(9.5f, Font.BOLD);
        final Font head = font(9, Font.BOLD);
        final Font label = font(8.5f, Font.BOLD);
        final Font body = font(8.5f, Font.NORMAL);
        final Font small = font(7.5f, Font.NORMAL);
    }

    /** 页脚：左留存声明 + 记录编号/打印时间，右「第 x 页 / 共 y 页」（总数在 onCloseDocument 回填） */
    private static final class FormFooter extends PdfPageEventHelper {
        private final Font font;
        private final String line1;
        private final String line2;
        private PdfTemplate total;
        /** 页数自计：PdfWriter.getPageNumber() 在 onCloseDocument 时已越过末页，不能直接用 */
        private int pages;

        FormFooter(Font font, String line1, String line2) {
            this.font = font;
            this.line1 = line1;
            this.line2 = line2;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(14, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            pages++;
            PdfContentByte cb = writer.getDirectContent();
            float left = document.leftMargin();
            float right = document.getPageSize().getWidth() - document.rightMargin();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(line1, font), left, 34, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(line2, font), left, 24, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("第 " + pages + " 页 / 共", font), right - 22, 24, 0);
            cb.addTemplate(total, right - 22, 24);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("页", font), right, 24, 0);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            String totalPages = String.valueOf(Math.max(pages, 1));
            BaseFont base = font.getBaseFont();
            float width = base == null ? 8 : base.getWidthPoint(totalPages, font.getSize());
            total.beginText();
            total.setFontAndSize(base, font.getSize());
            total.setTextMatrix(14 - width, 0);
            total.showText(totalPages);
            total.endText();
        }
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

    /** 单位后缀：空值给默认单位，避免出现"1000null" */
    private static String unitOf(String unit, String fallback) {
        return " " + (unit == null || unit.isBlank() ? fallback : unit);
    }

    /** 签字位标题：有名写「维保人员 1（张伟）签字」，无名留「（签字）」待手签 */
    private static String signCaption(String role, String name) {
        return name == null || name.isBlank() ? role + "（签字）" : role + "（" + name + "）签字";
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
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

    /** 维保单位名称：取维保单位档案（名称经 2.2 以平台返回为准回填），回退签退冻结值，缺失时占位 */
    private String companyNameOf(MaintainRecord r) {
        Company c = companyMapper.selectList(null).stream().findFirst().orElse(null);
        if (c != null && notBlank(c.name)) {
            return c.name;
        }
        try {
            if (r.previewContextJson != null && !r.previewContextJson.isEmpty()) {
                String name = str(JsonUtil.readMap(r.previewContextJson).get("companyName"));
                if (notBlank(name)) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.warn("预览上下文解析失败，维保单位名称使用占位：{}", e.getMessage());
        }
        return "（维保单位名称）";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
