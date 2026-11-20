package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.HandoverArchive;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.entity.HandoverSign;
import com.cqwlw.maintenance.mapper.HandoverArchiveMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.HandoverSignMapper;
import com.cqwlw.maintenance.service.HandoverChecklistService;
import com.cqwlw.maintenance.service.HandoverChecklistTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-12：GB/T 10060—2023 74 项移交检查清单。
 * 覆盖 74 项生成/幂等、7.2/7.3 五路径判定、不合格项 1:1 整改、逐条复验、☆NA 经理审批、缺项/缺描述/缺照片拦截、归档 SHA-256。
 */
class HandoverChecklistServiceTest {

    private final HandoverOrderMapper orderMapper = mock(HandoverOrderMapper.class);
    private final HandoverCheckMapper checkMapper = mock(HandoverCheckMapper.class);
    private final HandoverCheckItemMapper itemMapper = mock(HandoverCheckItemMapper.class);
    private final HandoverRectificationMapper rectMapper = mock(HandoverRectificationMapper.class);
    private final HandoverSignMapper signMapper = mock(HandoverSignMapper.class);
    private final HandoverArchiveMapper archiveMapper = mock(HandoverArchiveMapper.class);

    private HandoverChecklistService svc() {
        return new HandoverChecklistService(orderMapper, checkMapper, itemMapper, rectMapper, signMapper, archiveMapper);
    }

    private HandoverOrder order(String status) {
        HandoverOrder o = new HandoverOrder();
        o.id = "ho_1";
        o.status = status;
        when(orderMapper.selectById("ho_1")).thenReturn(o);
        return o;
    }

    /** 由模板构造 74 项草稿（submitChecklist 经 itemMapper.selectList 读回）。 */
    private List<HandoverCheckItem> templateItems() {
        List<HandoverCheckItem> list = new ArrayList<>();
        for (HandoverChecklistTemplate.Item t : HandoverChecklistTemplate.items()) {
            HandoverCheckItem it = new HandoverCheckItem();
            it.id = "hi_" + t.no();
            it.checkId = "hc_test";
            it.itemNo = t.no();
            it.itemGroup = t.group();
            it.important = t.important();
            list.add(it);
        }
        return list;
    }

    /** 全 74 项 PASS（试验项带实测值）。 */
    private List<Map<String, Object>> allPass() {
        List<Map<String, Object>> sub = new ArrayList<>();
        for (HandoverChecklistTemplate.Item t : HandoverChecklistTemplate.items()) {
            Map<String, Object> m = new HashMap<>();
            m.put("itemNo", t.no());
            m.put("result", "PASS");
            m.put("measuredValue", "1.0");
            sub.add(m);
        }
        return sub;
    }

    private void stubInspecting() {
        order("INSPECTING");
        when(checkMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(itemMapper.selectList(any())).thenReturn(templateItems());
    }

    private static Map<String, Object> cond(boolean site, boolean tri, String measures) {
        Map<String, Object> c = new HashMap<>();
        c.put("siteLimited", site);
        c.put("tripartiteAgreed", tri);
        c.put("controlMeasures", measures);
        return c;
    }

    // ---------- 7.2/7.3 五路径判定（纯函数） ----------

    @Test
    void judgeCoversFivePaths() {
        assertEquals(HandoverChecklistService.QUALIFIED, HandoverChecklistService.judge(0, 0, null));
        assertEquals(HandoverChecklistService.DISQUALIFIED, HandoverChecklistService.judge(1, 0, null), "重要项任一不合格即不合格");
        assertEquals(HandoverChecklistService.DISQUALIFIED, HandoverChecklistService.judge(0, 9, null), "一般项>8 不合格");
        assertEquals(HandoverChecklistService.CONDITIONAL,
                HandoverChecklistService.judge(0, 2, cond(true, true, "限制作业区域")), "≤3 且三条件齐备→有条件合格");
        assertEquals(HandoverChecklistService.RECTIFY, HandoverChecklistService.judge(0, 2, null), "一般项 1—3 无三条件→整改");
        assertEquals(HandoverChecklistService.RECTIFY, HandoverChecklistService.judge(0, 4, cond(true, true, "措施")), "一般项 4—8→整改");
        assertEquals(HandoverChecklistService.RECTIFY,
                HandoverChecklistService.judge(0, 1, cond(true, false, "措施")), "三条件缺一→整改");
    }

    // ---------- 74 项生成 ----------

    @Test
    void generateCreates74Items() {
        order("INSPECTING");
        when(checkMapper.selectList(any())).thenReturn(new ArrayList<>());
        HandoverCheck check = svc().generateChecklist("ho_1");
        assertEquals(74, check.totalItems);
        verify(itemMapper, times(74)).insert(any(HandoverCheckItem.class));
        verify(checkMapper, times(1)).insert(any(HandoverCheck.class));
        // result 列 NOT NULL 无默认值：生成时必须置空串（未填写），否则真实库插入失败、清单 0 项（2026-11-20 真机缺陷）
        org.mockito.ArgumentCaptor<HandoverCheckItem> captor = org.mockito.ArgumentCaptor.forClass(HandoverCheckItem.class);
        verify(itemMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        for (HandoverCheckItem it : captor.getAllValues()) {
            assertEquals("", it.result);
        }
    }

    @Test
    void generateIsIdempotent() {
        order("INSPECTING");
        HandoverCheck existing = new HandoverCheck();
        existing.id = "hc_old";
        existing.handoverId = "ho_1";
        when(checkMapper.selectList(any())).thenReturn(List.of(existing));
        when(itemMapper.selectCount(any())).thenReturn(74L);
        HandoverCheck out = svc().generateChecklist("ho_1");
        assertEquals("hc_old", out.id);
        verify(itemMapper, never()).insert(any(HandoverCheckItem.class));
    }

    /** 0 项缺陷行自愈（2026-11-20 真机缺陷：result 未置空串时代生成失败留下的空清单）：查验中删除重建 */
    @Test
    void generateHealsZeroItemCheck() {
        order("INSPECTING");
        HandoverCheck poisoned = new HandoverCheck();
        poisoned.id = "hc_bad";
        poisoned.handoverId = "ho_1";
        when(checkMapper.selectList(any())).thenReturn(List.of(poisoned));
        when(itemMapper.selectCount(any())).thenReturn(0L);
        HandoverCheck out = svc().generateChecklist("ho_1");
        org.junit.jupiter.api.Assertions.assertNotEquals("hc_bad", out.id);
        verify(checkMapper).deleteById("hc_bad");
        verify(itemMapper, times(74)).insert(any(HandoverCheckItem.class));
    }

    /** 提交齐备基线为模板 74 项（2026-11-20 真机缺陷：0 项缺陷行曾空转判定 QUALIFIED 并推进状态） */
    @Test
    void submitRejectsAbnormalItemCount() {
        order("INSPECTING");
        HandoverCheck check = new HandoverCheck();
        check.id = "hc_1";
        when(checkMapper.selectList(any())).thenReturn(List.of(check));
        when(itemMapper.selectCount(any())).thenReturn(74L);
        when(itemMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(new HandoverCheckItem())));
        BizException e = assertThrows(BizException.class,
                () -> svc().submitChecklist("ho_1", List.of(), Map.of()));
        assertEquals(422, e.getCode());
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("数据异常"));
    }

    @Test
    void generateRejectsNonInspecting() {
        order("DOC_SUBMITTING");
        BizException e = assertThrows(BizException.class, () -> svc().generateChecklist("ho_1"));
        assertEquals(422, e.getCode());
    }

    @Test
    void templateHas74ItemsAnd49Important() {
        List<HandoverChecklistTemplate.Item> items = HandoverChecklistTemplate.items();
        assertEquals(74, items.size());
        long important = items.stream().filter(HandoverChecklistTemplate.Item::important).count();
        assertEquals(49, important, "☆重要项应为 49");
    }

    // ---------- 提交判定 + 1:1 整改 ----------

    @Test
    void submitAllPassQualified() {
        stubInspecting();
        HandoverOrder o = svc().submitChecklist("ho_1", allPass(), null);
        assertEquals("PENDING_CONFIRM", o.status);
        ArgumentCaptor<HandoverCheck> cap = ArgumentCaptor.forClass(HandoverCheck.class);
        verify(checkMapper).updateById(cap.capture());
        assertEquals(HandoverChecklistService.QUALIFIED, cap.getValue().conclusion);
        assertEquals(74, cap.getValue().passCount);
        verify(rectMapper, never()).insert(any(HandoverRectification.class));
    }

    @Test
    void submitImportantFailDisqualifiedAndOneToOneRect() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        sub.set(1, failItem(2, "制动器制动力不足", "p1,p2")); // 第 2 项为☆重要项
        HandoverOrder o = svc().submitChecklist("ho_1", sub, null);
        assertEquals("RECTIFYING", o.status);
        ArgumentCaptor<HandoverCheck> cc = ArgumentCaptor.forClass(HandoverCheck.class);
        verify(checkMapper).updateById(cc.capture());
        assertEquals(HandoverChecklistService.DISQUALIFIED, cc.getValue().conclusion);
        assertEquals(1, cc.getValue().importantFailCount);
        ArgumentCaptor<HandoverRectification> rc = ArgumentCaptor.forClass(HandoverRectification.class);
        verify(rectMapper, times(1)).insert(rc.capture());
        assertEquals(2, rc.getValue().itemNo, "整改按 item_no 1:1 映射");
        assertEquals("INSPECT", rc.getValue().source);
    }

    @Test
    void submitGeneralFailRectify() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        sub.set(0, failItem(1, "标识不清", null)); // 第 1 项非重要、非试验，无需照片
        HandoverOrder o = svc().submitChecklist("ho_1", sub, null);
        assertEquals("RECTIFYING", o.status);
        ArgumentCaptor<HandoverCheck> cc = ArgumentCaptor.forClass(HandoverCheck.class);
        verify(checkMapper).updateById(cc.capture());
        assertEquals(HandoverChecklistService.RECTIFY, cc.getValue().conclusion);
        verify(rectMapper, times(1)).insert(any(HandoverRectification.class));
    }

    private static Map<String, Object> failItem(int no, String desc, String photos) {
        Map<String, Object> m = new HashMap<>();
        m.put("itemNo", no);
        m.put("result", "FAIL");
        m.put("failDesc", desc);
        if (photos != null) m.put("photoFileIds", photos);
        return m;
    }

    // ---------- 缺项/缺描述/缺照片/试验实测值拦截 ----------

    @Test
    void submitMissingResultBlocked() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        sub.remove(sub.size() - 1); // 缺第 74 项
        BizException e = assertThrows(BizException.class, () -> svc().submitChecklist("ho_1", sub, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未填结果"));
    }

    @Test
    void submitFailWithoutDescBlocked() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        sub.set(0, failItem(1, null, null));
        BizException e = assertThrows(BizException.class, () -> svc().submitChecklist("ho_1", sub, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("描述"));
    }

    @Test
    void submitImportantFailWithoutPhotoBlocked() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        sub.set(1, failItem(2, "制动器异常", null)); // ☆项缺照片
        BizException e = assertThrows(BizException.class, () -> svc().submitChecklist("ho_1", sub, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("照片"));
    }

    @Test
    void submitTestItemPassNeedsMeasuredValue() {
        stubInspecting();
        List<Map<String, Object>> sub = allPass();
        Map<String, Object> m = new HashMap<>();
        m.put("itemNo", 57); // 试验项
        m.put("result", "PASS");
        sub.set(56, m);
        BizException e = assertThrows(BizException.class, () -> svc().submitChecklist("ho_1", sub, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("实测值"));
    }

    // ---------- ☆项 NA 经理审批 ----------

    @Test
    void submitImportantNaRequiresApproval() {
        stubInspecting();
        when(signMapper.selectCount(any())).thenReturn(0L);
        List<Map<String, Object>> sub = allPass();
        Map<String, Object> m = new HashMap<>();
        m.put("itemNo", 2);
        m.put("result", "NA");
        m.put("naReason", "NA-01");
        sub.set(1, m);
        BizException e = assertThrows(BizException.class, () -> svc().submitChecklist("ho_1", sub, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("审批"));
    }

    @Test
    void submitImportantNaWithApprovalPasses() {
        stubInspecting();
        when(signMapper.selectCount(any())).thenReturn(1L);
        List<Map<String, Object>> sub = allPass();
        Map<String, Object> m = new HashMap<>();
        m.put("itemNo", 2);
        m.put("result", "NA");
        m.put("naReason", "NA-01");
        sub.set(1, m);
        HandoverOrder o = svc().submitChecklist("ho_1", sub, null);
        assertEquals("PENDING_CONFIRM", o.status, "NA 非不合格，审批后应合格");
    }

    @Test
    void naApproveWritesSignNode() {
        HandoverSign s = svc().naApprove("ho_1", Map.of("approverId", "m1", "approverName", "经理"));
        assertEquals("NA_APPROVE", s.nodeType);
        assertEquals("APPROVE", s.action);
        verify(signMapper, times(1)).insert(any(HandoverSign.class));
    }

    // ---------- 逐条复验 ----------

    @Test
    void recheckAllPassReturnsPendingConfirm() {
        order("RECTIFYING");
        HandoverRectification r = rect("hr_1", "PASS");
        when(rectMapper.selectById("hr_1")).thenReturn(r);
        when(rectMapper.selectList(any())).thenReturn(List.of(r));
        HandoverOrder o = svc().recheckItem("ho_1", "hr_1", Map.of("result", "PASS", "recheckerId", "c1"));
        assertEquals("PENDING_CONFIRM", o.status);
    }

    @Test
    void recheckPartialStaysRectifying() {
        order("RECTIFYING");
        HandoverRectification r1 = rect("hr_1", "PASS");
        HandoverRectification r2 = rect("hr_2", null);
        when(rectMapper.selectById("hr_1")).thenReturn(r1);
        when(rectMapper.selectList(any())).thenReturn(List.of(r1, r2));
        HandoverOrder o = svc().recheckItem("ho_1", "hr_1", Map.of("result", "PASS"));
        assertEquals("RECTIFYING", o.status, "尚有未复验项，保持整改中");
    }

    @Test
    void recheckRejectsWrongHandover() {
        order("RECTIFYING");
        HandoverRectification r = rect("hr_1", null);
        r.handoverId = "ho_other";
        when(rectMapper.selectById("hr_1")).thenReturn(r);
        BizException e = assertThrows(BizException.class,
                () -> svc().recheckItem("ho_1", "hr_1", Map.of("result", "PASS")));
        assertEquals(404, e.getCode());
    }

    private static HandoverRectification rect(String id, String recheck) {
        HandoverRectification r = new HandoverRectification();
        r.id = id;
        r.handoverId = "ho_1";
        r.itemNo = 1;
        r.voided = false;
        r.recheckResult = recheck;
        return r;
    }

    // ---------- 归档 SHA-256 ----------

    @Test
    void archiveCreatesSevenEntriesWithSha256() {
        when(archiveMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(checkMapper.selectList(any())).thenReturn(new ArrayList<>());
        List<HandoverArchive> out = svc().archive("ho_1");
        assertEquals(7, out.size());
        verify(archiveMapper, times(7)).insert(any(HandoverArchive.class));
        for (HandoverArchive a : out) {
            assertEquals(64, a.sha256.length(), "SHA-256 十六进制应 64 位");
        }
    }

    @Test
    void archiveIsIdempotent() {
        HandoverArchive existing = new HandoverArchive();
        existing.id = "ha_1";
        existing.handoverId = "ho_1";
        when(archiveMapper.selectList(any())).thenReturn(List.of(existing));
        List<HandoverArchive> out = svc().archive("ho_1");
        assertEquals(1, out.size());
        verify(archiveMapper, never()).insert(any(HandoverArchive.class));
    }
}