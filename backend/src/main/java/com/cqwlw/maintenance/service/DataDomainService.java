package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.BizExportApproval;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.mapper.BizExportApprovalMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataDomainService {

    private final SysConfigMapper configMapper;
    private final BizExportApprovalMapper approvalMapper;
    private final SysAuditLogMapper auditMapper;
    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper workOrderMapper;
    private final MaintainRecordMapper recordMapper;
    private final AuditLogService audit;

    public DataDomainService(SysConfigMapper configMapper, BizExportApprovalMapper approvalMapper,
                             SysAuditLogMapper auditMapper, ElevatorMapper elevatorMapper,
                             WorkOrderMapper workOrderMapper, MaintainRecordMapper recordMapper,
                             AuditLogService audit) {
        this.configMapper = configMapper;
        this.approvalMapper = approvalMapper;
        this.auditMapper = auditMapper;
        this.elevatorMapper = elevatorMapper;
        this.workOrderMapper = workOrderMapper;
        this.recordMapper = recordMapper;
        this.audit = audit;
    }

    public Map<String, String> config() {
        Map<String, String> out = new LinkedHashMap<>();
        for (SysConfig c : configMapper.selectList(null)) {
            out.put(c.cfgKey, c.cfgValue);
        }
        return out;
    }

    public SysConfig setConfig(String key, String value, String actor) {
        validateRange(key, value);
        SysConfig c = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getCfgKey, key));
        if (c == null) {
            c = new SysConfig();
            c.id = Ids.next("cfg");
            c.cfgKey = key;
        }
        c.cfgValue = value;
        c.updatedBy = actor;
        c.updatedAt = TimeUtil.now();
        if (configMapper.selectById(c.id) == null) {
            configMapper.insert(c);
        } else {
            configMapper.updateById(c);
        }
        audit(actor, "config:manage", key, "SUCCESS", value);
        return c;
    }

    private static void validateRange(String key, String value) {
        int v;
        try {
            v = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return; // 非数值配置（脱敏开关/签字模板）不校验范围
        }
        if ("sla_minutes".equals(key) && (v < 1 || v > 240)) throw new BizException(422, "SLA 须 1—240 分钟");
        if ("maint_cycle_days".equals(key) && (v < 1 || v > 90)) throw new BizException(422, "保养周期须 1—90 天");
        if ("spotcheck_ratio".equals(key) && (v < 1 || v > 100)) throw new BizException(422, "抽查比例须 1—100");
    }

    public BizExportApproval requestExport(String purpose, String scope, String actor) {
        if (purpose == null || purpose.length() < 10) {
            throw new BizException(422, "用途须≥10字");
        }
        BizExportApproval a = new BizExportApproval();
        a.id = Ids.next("exp");
        a.applicant = actor;
        a.purpose = purpose;
        a.scope = scope;
        a.status = "PENDING";
        a.createdAt = TimeUtil.now();
        approvalMapper.insert(a);
        audit(actor, "data:export_sensitive", a.id, "SUCCESS", purpose);
        return a;
    }

    public BizExportApproval approveExport(String id, String actor) {
        BizExportApproval a = approvalMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "审批单不存在");
        }
        if (!"PENDING".equals(a.status)) {
            throw new BizException(422, "仅待审批可操作");
        }
        a.status = "APPROVED";
        a.approvedBy = actor;
        a.approvedAt = TimeUtil.now();
        approvalMapper.updateById(a);
        audit(actor, "data:export_sensitive", id, "SUCCESS", "审批通过");
        return a;
    }

    public List<BizExportApproval> approvals() {
        return approvalMapper.selectList(new LambdaQueryWrapper<BizExportApproval>().orderByDesc(BizExportApproval::getCreatedAt));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elevators", elevatorMapper.selectCount(null));
        out.put("workOrders", workOrderMapper.selectCount(null));
        out.put("records", recordMapper.selectCount(null));
        return out;
    }

    public List<SysAuditLog> auditLog(int limit) {
        return auditMapper.selectList(new LambdaQueryWrapper<SysAuditLog>()
                .orderByDesc(SysAuditLog::getCreatedAt).last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    private void audit(String actor, String action, String target, String result, String reason) {
        SysAuditLog a = new SysAuditLog();
        a.actorId = actor;
        a.action = action;
        a.target = target;
        a.result = result;
        a.reason = reason;
        audit.append(a);
    }
}