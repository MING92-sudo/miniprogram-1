package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 限时授权（spec §5.1）：PENDING→ACTIVE→REVOKED/WITHDRAWN，到期自动回收 + 递增 scope_version + 审计。
 * 回收是授权时效，与 AGENTS §2.4「上报无自动重试」无关；不触派单（AGENTS §3.6）。
 * 发起/撤回管理端点随 M3 #/grants；M1 交付机制 + 测试夹具。
 */
@Service
public class GrantService {

    private static final Logger log = LoggerFactory.getLogger(GrantService.class);

    private final SysGrantMapper grantMapper;
    private final EmployeeMapper employeeMapper;
    private final AuditLogService auditLogService;

    public GrantService(SysGrantMapper grantMapper, EmployeeMapper employeeMapper,
                        AuditLogService auditLogService) {
        this.grantMapper = grantMapper;
        this.employeeMapper = employeeMapper;
        this.auditLogService = auditLogService;
    }

    public SysGrant create(String granteeId, String permCode, String scope, String scopeRef,
                           LocalDateTime start, LocalDateTime end, String grantedBy, String reason) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(422, "授权结束时间必须晚于开始时间");
        }
        LocalDateTime now = TimeUtil.now();
        SysGrant g = new SysGrant();
        g.id = Ids.next("grant");
        g.granteeId = granteeId;
        g.permCode = permCode;
        g.scope = scope;
        g.scopeRef = scopeRef;
        g.startAt = start;
        g.endAt = end;
        g.status = start.isAfter(now) ? "PENDING" : "ACTIVE";
        g.grantedBy = grantedBy;
        g.reason = reason;
        g.createdAt = now;
        grantMapper.insert(g);
        appendAudit(grantedBy, "grant:create", g.id, "SUCCESS", reason);
        return g;
    }

    public void withdraw(String grantId, String actorId) {
        SysGrant g = grantMapper.selectById(grantId);
        if (g == null) {
            throw new BizException(404, "授权不存在");
        }
        if ("ACTIVE".equals(g.status) || "PENDING".equals(g.status)) {
            g.status = "WITHDRAWN";
            grantMapper.updateById(g);
            employeeMapper.bumpScopeVersion(g.granteeId);
            appendAudit(actorId, "grant:withdraw", grantId, "SUCCESS", g.reason);
        }
    }

    /** 到期回收 + 待激活激活（独立 cron，沿用 DispatchService 模式；不触派单）。 */
    @Scheduled(cron = "0 */5 * * * ?", zone = "Asia/Shanghai")
    public int revokeExpired() {
        LocalDateTime now = TimeUtil.now();
        List<SysGrant> candidates = grantMapper.selectList(new LambdaQueryWrapper<SysGrant>()
                .in(SysGrant::getStatus, "ACTIVE", "PENDING"));
        int n = 0;
        for (SysGrant g : candidates) {
            if ("ACTIVE".equals(g.status) && !g.endAt.isAfter(now)) {
                g.status = "REVOKED";
                grantMapper.updateById(g);
                employeeMapper.bumpScopeVersion(g.granteeId);
                appendAudit("system", "grant:revoke", g.id, "SUCCESS", "到期自动回收");
                n++;
            } else if ("PENDING".equals(g.status) && !g.startAt.isAfter(now)) {
                g.status = "ACTIVE";
                grantMapper.updateById(g);
                appendAudit("system", "grant:activate", g.id, "SUCCESS", null);
                n++;
            }
        }
        return n;
    }

    /** 解析集成：当前 ACTIVE 授权 perm_code 并集（仅 [start,end) 内，状态已含时效）。 */
    public Set<String> activePermCodes(String granteeId) {
        Set<String> codes = new LinkedHashSet<>();
        for (SysGrant g : grantMapper.selectList(new LambdaQueryWrapper<SysGrant>()
                .eq(SysGrant::getGranteeId, granteeId)
                .eq(SysGrant::getStatus, "ACTIVE"))) {
            codes.add(g.permCode);
        }
        return codes;
    }

    private void appendAudit(String actorId, String action, String target, String result, String reason) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.actorId = actorId;
            a.action = action;
            a.target = target;
            a.result = result;
            a.reason = reason;
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            log.warn("授权审计落库失败（不影响主流程）: {}", ex.getMessage());
        }
    }
}