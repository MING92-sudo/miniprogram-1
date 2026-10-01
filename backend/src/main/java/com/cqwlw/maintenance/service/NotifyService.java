package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.NotifyRecord;
import com.cqwlw.maintenance.mapper.NotifyRecordMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅消息/站内消息发送记录。本地开发只落发送记录（status=SENT 表示已入队/模拟）；
 * 困人救援不走订阅消息（电话+短信+弹窗）。
 */
@Service
public class NotifyService {

    private final NotifyRecordMapper recordMapper;

    public NotifyService(NotifyRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    public Map<String, Object> listRecords(Map<String, String> q) {
        LambdaQueryWrapper<NotifyRecord> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("type"))) {
            w.eq(NotifyRecord::getType, q.get("type"));
        }
        if (notBlank(q.get("channel"))) {
            w.eq(NotifyRecord::getChannel, q.get("channel"));
        }
        if (notBlank(q.get("status"))) {
            w.eq(NotifyRecord::getStatus, q.get("status"));
        }
        w.orderByDesc(NotifyRecord::getCreatedAt);
        List<NotifyRecord> all = recordMapper.selectList(w);
        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (NotifyRecord r : all.subList(from, to)) {
            list.add(row(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** 落一条发送记录（SUBSCRIBE 模拟成功；INBOX 站内即成功）。真实微信推送云端接入后改状态机 */
    public Map<String, Object> send(String type, String title, String content,
                                    String targetEmployeeId, String targetRole,
                                    String templateId, String channel) {
        NotifyRecord r = new NotifyRecord();
        r.id = Ids.next("nt");
        r.type = type == null ? "" : type;
        r.templateId = templateId;
        r.targetEmployeeId = targetEmployeeId;
        r.targetRole = targetRole;
        r.title = title == null ? "" : title;
        r.content = content == null ? "" : content;
        r.channel = channel == null || channel.isBlank() ? "SUBSCRIBE" : channel;
        r.status = "SENT";
        r.createdAt = TimeUtil.now();
        recordMapper.insert(r);
        return row(r);
    }

    private Map<String, Object> row(NotifyRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("type", r.type);
        m.put("templateId", r.templateId == null ? "" : r.templateId);
        m.put("targetEmployeeId", r.targetEmployeeId == null ? "" : r.targetEmployeeId);
        m.put("targetRole", r.targetRole == null ? "" : r.targetRole);
        m.put("title", r.title);
        m.put("content", r.content);
        m.put("channel", r.channel);
        m.put("status", r.status);
        m.put("createdAt", TimeUtil.format(r.createdAt));
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
