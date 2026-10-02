package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.util.TimeUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单发号：自动派单与管理端人工指派共用同一发号点。
 * 序号按**当日**工单数推算，且撞唯一键必须递增重试——人工指派原先用全表总数取模、
 * 撞键直接 500，与自动派单是两套实现，会互相撞号。
 */
class OrderNoIssuerTest {

    private static final String TODAY = TimeUtil.date(LocalDateTime.now()).replace("-", "");

    private WorkOrderMapper orderMapper;
    private OrderNoIssuer issuer;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkOrder.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(WorkOrderMapper.class);
        issuer = new OrderNoIssuer(orderMapper);
    }

    private WorkOrder newOrder() {
        WorkOrder o = new WorkOrder();
        o.id = "wo_1";
        o.elevatorId = "el_1";
        return o;
    }

    @Test
    void issuesDayPrefixedSequentialNumber() {
        when(orderMapper.selectCount(any())).thenReturn(0L);

        WorkOrder o = newOrder();
        issuer.insert(o);

        assertEquals("WO" + TODAY + "-001", o.orderNo);
        verify(orderMapper).insert(o);
    }

    @Test
    void sequenceFollowsSameDayOrderCount() {
        when(orderMapper.selectCount(any())).thenReturn(7L);

        WorkOrder o = newOrder();
        issuer.insert(o);

        assertEquals("WO" + TODAY + "-008", o.orderNo);
    }

    @Test
    void countsOnlySameDayOrders() {
        when(orderMapper.selectCount(any())).thenReturn(0L);

        issuer.insert(newOrder());

        ArgumentCaptor<LambdaQueryWrapper<WorkOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("order_no"), sql);
        assertTrue(sql.contains("LIKE"), sql);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("WO" + TODAY + "%"),
                captor.getValue().getParamNameValuePairs().toString());
    }

    @Test
    void retriesWithIncrementedSequenceOnDuplicateKey() {
        when(orderMapper.selectCount(any())).thenReturn(0L);
        WorkOrder o = newOrder();
        when(orderMapper.insert(o))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"))
                .thenReturn(1);

        issuer.insert(o);

        // 首次冲突后必须换序号重试落库，否则唯一键异常会中断整个派单/派工循环
        verify(orderMapper, times(2)).insert(o);
        assertEquals("WO" + TODAY + "-002", o.orderNo);
    }

    @Test
    void givesUpAfterFiveAttempts() {
        when(orderMapper.selectCount(any())).thenReturn(0L);
        WorkOrder o = newOrder();
        when(orderMapper.insert(o))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> issuer.insert(o));

        verify(orderMapper, times(5)).insert(o);
    }
}