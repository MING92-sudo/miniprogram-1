package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 签字页倒计时、待签列表剩余时间与到期提醒共用同一口径，三处不得各自为政。 */
@Service
public class ClientSignSlaService {

    public static final String CFG_KEY = "client_sign_sla_hours";
    public static final int DEFAULT_SLA_HOURS = 72;

    private final SysConfigMapper configMapper;

    public ClientSignSlaService(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public int slaHours() {
        SysConfig c = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getCfgKey, CFG_KEY).last("LIMIT 1"));
        if (c != null && c.cfgValue != null) {
            try {
                int v = Integer.parseInt(c.cfgValue.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 非法值回退默认
            }
        }
        return DEFAULT_SLA_HOURS;
    }

    public LocalDateTime baseOf(MaintainRecord r) {
        return r.signRequestedAt != null ? r.signRequestedAt : r.createdAt;
    }

    /** 签字截止时刻；起算缺失（异常数据）返回 null——调用方按「无截止」处理，不误报超期 */
    public LocalDateTime deadlineOf(MaintainRecord r) {
        return deadlineOf(r, slaHours());
    }

    /** 批量口径：调用方一次性取 slaHours 逐条推算（定时扫描等场景避免每行重查 sys_config） */
    public LocalDateTime deadlineOf(MaintainRecord r, int slaHours) {
        LocalDateTime base = baseOf(r);
        return base == null ? null : base.plusHours(slaHours);
    }

    public boolean overdue(MaintainRecord r, LocalDateTime now) {
        LocalDateTime deadline = deadlineOf(r);
        return deadline != null && now.isAfter(deadline);
    }
}
