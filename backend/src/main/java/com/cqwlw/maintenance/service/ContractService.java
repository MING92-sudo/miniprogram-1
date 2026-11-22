package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ContractElevator;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.mapper.ContractElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintenanceContractMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ContractService {

    private final MaintenanceContractMapper contractMapper;
    private final ContractElevatorMapper contractElevatorMapper;

    public ContractService(MaintenanceContractMapper contractMapper,
                           ContractElevatorMapper contractElevatorMapper) {
        this.contractMapper = contractMapper;
        this.contractElevatorMapper = contractElevatorMapper;
    }

    public List<MaintenanceContract> list() {
        return contractMapper.selectList(new LambdaQueryWrapper<MaintenanceContract>()
                .orderByDesc(MaintenanceContract::getStartDate).orderByDesc(MaintenanceContract::getId));
    }

    public MaintenanceContract get(String id) {
        MaintenanceContract c = contractMapper.selectById(id);
        if (c == null) {
            throw new BizException(1404, "合同不存在");
        }
        return c;
    }

    public MaintenanceContract create(Map<String, Object> body) {
        String contractNo = str(body, "contractNo");
        if (contractNo == null || contractNo.isBlank()) {
            throw new BizException(422, "合同号不能为空");
        }
        String useUnitId = str(body, "useUnitId");
        if (useUnitId == null || useUnitId.isBlank()) {
            throw new BizException(422, "请选择使用单位");
        }
        LocalDate startDate = date(body, "startDate", "合同起始日不能为空");
        LocalDate endDate = date(body, "endDate", "合同终止日不能为空");
        if (startDate.isAfter(LocalDate.now())) {
            throw new BizException(422, "合同起始日不能晚于建档日");
        }
        if (endDate.isBefore(startDate)) {
            throw new BizException(422, "合同终止日不能早于起始日");
        }

        List<String> elevatorIds = strList(body, "elevatorIds");
        if (elevatorIds != null && !elevatorIds.isEmpty()) {
            List<ContractElevator> links = contractElevatorMapper.selectList(
                    new LambdaQueryWrapper<ContractElevator>()
                            .in(ContractElevator::getElevatorId, elevatorIds));
            if (links != null && !links.isEmpty()) {
                List<String> contractIds = links.stream()
                        .map(l -> l.contractId).distinct().toList();
                // 生效=ACTIVE 且未过期（end_date ≥ 今天），与 getActiveContractByElevator 对齐，过期放行续签
                Long active = contractMapper.selectCount(
                        new LambdaQueryWrapper<MaintenanceContract>()
                                .in(MaintenanceContract::getId, contractIds)
                                .eq(MaintenanceContract::getStatus, "ACTIVE")
                                .ge(MaintenanceContract::getEndDate, LocalDate.now()));
                if (active != null && active > 0) {
                    throw new BizException(422, "电梯已有生效合同");
                }
            }
        }

        MaintenanceContract c = new MaintenanceContract();
        c.id = Ids.next("ct");
        c.contractNo = contractNo.trim();
        c.useUnitId = useUnitId.trim();
        c.startDate = startDate;
        c.endDate = endDate;
        c.status = "ACTIVE";
        c.amount = decimalOrNull(body == null ? null : body.get("amount"));
        contractMapper.insert(c);

        if (elevatorIds != null) {
            for (String elevatorId : elevatorIds) {
                ContractElevator ce = new ContractElevator();
                ce.id = Ids.next("ce");
                ce.contractId = c.id;
                ce.elevatorId = elevatorId;
                contractElevatorMapper.insert(ce);
            }
        }
        return c;
    }

    public MaintenanceContract getActiveContractByElevator(String elevatorId) {
        List<ContractElevator> links = contractElevatorMapper.selectList(
                new LambdaQueryWrapper<ContractElevator>().eq(ContractElevator::getElevatorId, elevatorId));
        if (links == null || links.isEmpty()) {
            return null;
        }
        List<String> contractIds = links.stream().map(l -> l.contractId).distinct().toList();
        LocalDate today = LocalDate.now();
        List<MaintenanceContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<MaintenanceContract>()
                        .in(MaintenanceContract::getId, contractIds)
                        .eq(MaintenanceContract::getStatus, "ACTIVE")
                        .le(MaintenanceContract::getStartDate, today)
                        .ge(MaintenanceContract::getEndDate, today)
                        .orderByDesc(MaintenanceContract::getStartDate));
        return contracts.isEmpty() ? null : contracts.get(0);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static LocalDate date(Map<String, Object> body, String key, String missingMsg) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new BizException(422, missingMsg);
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (DateTimeParseException e) {
            throw new BizException(422, "日期格式应为 yyyy-MM-dd");
        }
    }

    private static List<String> strList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf)
                    .filter(s -> !s.isBlank()).toList();
        }
        return List.of(String.valueOf(v));
    }

    private static BigDecimal decimalOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "金额必须为数字");
        }
    }
}
