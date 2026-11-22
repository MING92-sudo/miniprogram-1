package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ContractElevator;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.mapper.ContractElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintenanceContractMapper;
import com.cqwlw.maintenance.service.ContractService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractServiceTest {

    private static ContractService service(MaintenanceContractMapper contractMapper,
                                           ContractElevatorMapper contractElevatorMapper) {
        return new ContractService(contractMapper, contractElevatorMapper);
    }

    @Test
    void rejectsContractStartingAfterFilingDate() {
        MaintenanceContractMapper contractMapper = mock(MaintenanceContractMapper.class);
        ContractService svc = service(contractMapper, mock(ContractElevatorMapper.class));

        BizException e = assertThrows(BizException.class, () -> svc.create(Map.of(
                "contractNo", "HT-2026-001",
                "useUnitId", "uu_1",
                "startDate", LocalDate.now().plusDays(1).toString(),
                "endDate", LocalDate.now().plusDays(365).toString())));
        assertEquals(422, e.getCode(), "合同起始日晚于建档日应拒绝");
        verify(contractMapper, never()).insert(any(MaintenanceContract.class));
    }

    @Test
    void rejectsOverlappingActiveContract() {
        MaintenanceContractMapper contractMapper = mock(MaintenanceContractMapper.class);
        ContractElevatorMapper contractElevatorMapper = mock(ContractElevatorMapper.class);
        ContractService svc = service(contractMapper, contractElevatorMapper);

        String elevatorId = "el_1";
        // 库中电梯 el_1 已关联 status=ACTIVE 的合同 ct_1（模拟第一个生效合同）
        ContractElevator link = new ContractElevator();
        link.id = "ce_1";
        link.contractId = "ct_1";
        link.elevatorId = elevatorId;

        // 第一次 create（无既有关联）成功落库；第二次 create 检测到同梯已有生效合同
        when(contractElevatorMapper.selectList(any())).thenReturn(List.of(), List.of(link));
        when(contractMapper.selectCount(any())).thenReturn(1L);

        svc.create(Map.of(
                "contractNo", "HT-2026-001",
                "useUnitId", "uu_1",
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(365).toString(),
                "elevatorIds", List.of(elevatorId)));

        BizException e = assertThrows(BizException.class, () -> svc.create(Map.of(
                "contractNo", "HT-2026-002",
                "useUnitId", "uu_1",
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(365).toString(),
                "elevatorIds", List.of(elevatorId))));
        assertEquals(422, e.getCode(), "同梯已有生效合同应拒绝");
        verify(contractMapper, times(1)).insert(any(MaintenanceContract.class));
    }
}
