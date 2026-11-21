package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户门户消息（V56，docs/04 V2.80）：message.use_unit_id 非空行为客户受众——
 * 签字临期/超期提醒由 SignReminderService 写入，客户在待办签字页查看并标记已读。
 * 数据域一律按 token 单位锚点收敛：读/标读均校验消息归属本单位，越权 1403 无副作用；
 * 员工消息中心（DirectoryService）只读 use_unit_id IS NULL 的行，两侧互不串扰。
 */
@Service
public class ClientMessageService {

    private static final int LIST_LIMIT = 50;

    private final MessageMapper messageMapper;

    public ClientMessageService(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /** 本单位消息（按时间倒序，上限 50） */
    public List<Map<String, Object>> list(String useUnitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (useUnitId == null || useUnitId.isBlank()) {
            return out;
        }
        List<Message> list = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getUseUnitId, useUnitId)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT));
        for (Message m : list == null ? List.<Message>of() : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id);
            row.put("title", m.title == null ? "" : m.title);
            row.put("content", m.content == null ? "" : m.content);
            row.put("createdAt", TimeUtil.format(m.createdAt));
            row.put("readFlag", Boolean.TRUE.equals(m.readFlag));
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> unreadCount(String useUnitId) {
        long n = 0;
        if (useUnitId != null && !useUnitId.isBlank()) {
            Long c = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                    .eq(Message::getUseUnitId, useUnitId)
                    .eq(Message::getReadFlag, false));
            n = c == null ? 0 : c;
        }
        return Map.of("count", n);
    }

    /** 标读（归属校验：消息不属本单位 → 1403，不泄露存在性以外的副作用） */
    public Map<String, Object> markRead(String useUnitId, String messageId) {
        Message m = messageMapper.selectById(messageId);
        if (m == null) {
            throw new BizException(1404, "消息不存在");
        }
        if (useUnitId == null || !useUnitId.equals(m.useUnitId)) {
            throw new BizException(1403, "仅可操作本单位消息");
        }
        if (!Boolean.TRUE.equals(m.readFlag)) {
            m.readFlag = true;
            messageMapper.updateById(m);
        }
        return Map.of("ok", true);
    }

    public Map<String, Object> markAllRead(String useUnitId) {
        int affected = 0;
        if (useUnitId != null && !useUnitId.isBlank()) {
            affected = messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getUseUnitId, useUnitId)
                    .eq(Message::getReadFlag, false)
                    .set(Message::getReadFlag, true));
        }
        return Map.of("ok", true, "updated", affected);
    }
}
