package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务审计（spec §5.2）：只 append + 查询，无删除/更新端点（只读不可删改）。
 * 与旧 op_log + AuditInterceptor 并存；落库失败由调用方吞异常（不影响主流程）。
 */
@Service
public class AuditLogService {

    private final SysAuditLogMapper mapper;

    public AuditLogService(SysAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void append(SysAuditLog entry) {
        if (entry.id == null || entry.id.isBlank()) {
            entry.id = Ids.next("audit");
        }
        if (entry.createdAt == null) {
            entry.createdAt = TimeUtil.now();
        }
        mapper.insert(entry);
    }

    public List<SysAuditLog> query(String actorId, String action, int limit) {
        LambdaQueryWrapper<SysAuditLog> w = new LambdaQueryWrapper<SysAuditLog>()
                .orderByDesc(SysAuditLog::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        if (actorId != null && !actorId.isBlank()) {
            w.eq(SysAuditLog::getActorId, actorId);
        }
        if (action != null && !action.isBlank()) {
            w.eq(SysAuditLog::getAction, action);
        }
        return mapper.selectList(w);
    }
}