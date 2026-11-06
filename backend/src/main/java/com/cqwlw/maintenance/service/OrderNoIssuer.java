package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 工单号的唯一发号点。自动派单与管理端人工指派都经此处落库，
 * 避免两处各写一套序号算法后互相撞号。
 */
@Service
public class OrderNoIssuer {

    private static final Logger log = LoggerFactory.getLogger(OrderNoIssuer.class);
    private static final int MAX_ATTEMPTS = 5;

    private final WorkOrderMapper orderMapper;

    public OrderNoIssuer(WorkOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 编号格式 {@code WO{yyyyMMdd}-{当日三位序号}}。序号 = 当日已有工单数 + 1，
     * 而 order_no 上有唯一索引：多台电梯同时到期时两个线程可能算出同一序号，
     * 故撞键时重试。重试必须重新统计当日工单数（并发对端提交后计数已变）仍取
     * 「计数 + 1」的下一个空闲号——旧实现用「计数 + attempt」把重试次数当偏移，
     * 会跳过重算出的紧邻空闲号，高并发下徒增冲突甚至耗尽重试上限。
     * 没有重试会让整个派单或派工循环被唯一键异常中断。
     */
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
