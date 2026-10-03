package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.SysParam;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import org.springframework.stereotype.Service;

/**
 * 签到定位阈值三级解析（docs/02 §5.4）：
 * 电梯级 `elevator.checkin_threshold` → 品种级 `sys_param['checkin.threshold.category.<品种>']`
 * → 全局 `sys_param['checkin.threshold.default']` → `app.checkin-threshold-meters`（默认 200 米）。
 * 品种级数值属业务/合规参数，未确认前不预置（缺行即回退全局），由运维按需写入 sys_param（V12 迁移注释含示例）。
 */
@Service
public class CheckinThresholdService {

    static final String KEY_GLOBAL = "checkin.threshold.default";
    static final String KEY_CATEGORY_PREFIX = "checkin.threshold.category.";

    private final SysParamMapper paramMapper;
    private final AppProperties props;

    public CheckinThresholdService(SysParamMapper paramMapper, AppProperties props) {
        this.paramMapper = paramMapper;
        this.props = props;
    }

    /** 返回本次签到应使用的阈值（米）；任一级配置非法（非数字/≤0）即继续回退 */
    public int resolve(Elevator el) {
        if (el != null && el.checkinThreshold != null && el.checkinThreshold > 0) {
            return el.checkinThreshold;
        }
        if (el != null && el.category != null && !el.category.isEmpty()) {
            Integer byCategory = paramInt(paramMapper.selectById(KEY_CATEGORY_PREFIX + el.category));
            if (byCategory != null) {
                return byCategory;
            }
        }
        Integer global = paramInt(paramMapper.selectById(KEY_GLOBAL));
        return global != null ? global : props.getCheckinThresholdMeters();
    }

    private static Integer paramInt(SysParam p) {
        if (p == null || p.paramValue == null || p.paramValue.trim().isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(p.paramValue.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
