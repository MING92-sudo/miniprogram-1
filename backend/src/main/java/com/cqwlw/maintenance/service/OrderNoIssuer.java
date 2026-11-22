package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderNoIssuer {

    private static final Logger log = LoggerFactory.getLogger(OrderNoIssuer.class);
    private static final int MAX_ATTEMPTS = 5;

    private final WorkOrderMapper orderMapper;

    public OrderNoIssuer(WorkOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public void insert(WorkOrder o) {
        String today = TimeUtil.date(TimeUtil.now()).replace("-", "");
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            o.orderNo = "WO" + today + "-" + String.format("%03d", todayOrderCount(today) + 1);
            try {
                orderMapper.insert(o);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("工单号连续 {} 次冲突, elevatorId={}", MAX_ATTEMPTS, o.elevatorId);
                    throw e;
                }
            }
        }
    }

    private long todayOrderCount(String todayCompact) {
        Long c = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .likeRight(WorkOrder::getOrderNo, "WO" + todayCompact));
        return c == null ? 0 : c;
    }
}
