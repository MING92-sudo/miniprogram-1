package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.SysParam;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 签到定位阈值三级解析（docs/02 §5.4）：电梯级 → 品种级（sys_param）→ 全局（sys_param）→ app 配置。
 * 品种级数值属业务/合规参数，未预置时必须安全回退，不得因缺行导致签到被误拦或放行无阈值。
 */
class CheckinThresholdServiceTest {

    private static final String CATEGORY = "液压驱动电梯";

    private SysParamMapper paramMapper;
    private AppProperties props;
    private CheckinThresholdService service;

    @BeforeEach
    void setUp() {
        paramMapper = mock(SysParamMapper.class);
        props = new AppProperties();
        props.setCheckinThresholdMeters(200);
        service = new CheckinThresholdService(paramMapper, props);
    }

    private static Elevator elevator(Integer threshold, String category) {
        Elevator el = new Elevator();
        el.checkinThreshold = threshold;
        el.category = category;
        return el;
    }

    private static SysParam param(String key, String value) {
        SysParam p = new SysParam();
        p.paramKey = key;
        p.paramValue = value;
        return p;
    }

    @Test
    void elevatorLevelWins() {
        when(paramMapper.selectById("checkin.threshold.category." + CATEGORY)).thenReturn(param("k", "300"));
        when(paramMapper.selectById("checkin.threshold.default")).thenReturn(param("k", "200"));
        assertEquals(50, service.resolve(elevator(50, CATEGORY)), "电梯级阈值最优先");
    }

    @Test
    void categoryLevelUsedWhenNoElevatorLevel() {
        when(paramMapper.selectById("checkin.threshold.category." + CATEGORY)).thenReturn(param("k", "300"));
        assertEquals(300, service.resolve(elevator(null, CATEGORY)), "电梯级为空时用品种级");
    }

    @Test
    void globalParamUsedWhenNoCategoryRow() {
        when(paramMapper.selectById("checkin.threshold.category." + CATEGORY)).thenReturn(null);
        when(paramMapper.selectById("checkin.threshold.default")).thenReturn(param("k", "150"));
        assertEquals(150, service.resolve(elevator(null, CATEGORY)), "无品种级配置时用 sys_param 全局值");
    }

    @Test
    void fallsBackToAppPropertiesWhenNoRows() {
        when(paramMapper.selectById("checkin.threshold.default")).thenReturn(null);
        assertEquals(200, service.resolve(elevator(null, CATEGORY)));
        props.setCheckinThresholdMeters(120);
        assertEquals(120, service.resolve(null), "电梯档案缺失时也必须给出阈值（回退 app 配置）");
    }

    @Test
    void illegalParamValuesIgnored() {
        when(paramMapper.selectById("checkin.threshold.category." + CATEGORY)).thenReturn(param("k", "abc"));
        when(paramMapper.selectById("checkin.threshold.default")).thenReturn(param("k", "0"));
        assertEquals(200, service.resolve(elevator(0, CATEGORY)), "非数字/≤0 的配置一律忽略并继续回退");
    }
}
