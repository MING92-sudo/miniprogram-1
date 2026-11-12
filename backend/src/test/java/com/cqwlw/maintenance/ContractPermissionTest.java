package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.controller.ContractController;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.service.ContractService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合同接口权限（Task 12/14）：原来一刀切 requireAdmin 让安装部读不到合同，
 * 导致小程序外接建档选不了“已生效合同”。改为按能力码：读=contract:read、写=contract:write。
 */
class ContractPermissionTest {

    private final ContractService contractService = mock(ContractService.class);
    private final ContractController controller = new ContractController(contractService);

    private static MockHttpServletRequest request(String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("role", role);
        return req;
    }

    /** 安装经理可读合同（外接建档前置），但不能建合同 */
    @Test
    void installManagerCanReadButNotWrite() {
        when(contractService.list()).thenReturn(List.of(new MaintenanceContract()));
        assertEquals(1, controller.list(request("INSTALL_MANAGER")).getData().size());

        BizException ex = assertThrows(BizException.class,
                () -> controller.create(java.util.Map.of("contractNo", "HT-1"), request("INSTALL_MANAGER")));
        assertEquals(403, ex.getCode());
    }

    /** 维保商务可建合同 */
    @Test
    void maintenanceBizCanWrite() {
        when(contractService.create(org.mockito.ArgumentMatchers.any())).thenReturn(new MaintenanceContract());
        assertEquals(0, controller.create(java.util.Map.of("contractNo", "HT-1"), request("MAINT_BIZ")).getCode());
    }

    /** 维保人员不可读合同；使用单位按 docs/14 §4「本单位合同读」放行 */
    @Test
    void workerCannotReadButUnitAdminCan() {
        assertEquals(403, assertThrows(BizException.class,
                () -> controller.list(request("WORKER"))).getCode());
        when(contractService.list()).thenReturn(List.of(new MaintenanceContract()));
        assertEquals(0, controller.list(request("UNIT_ADMIN")).getCode());
    }
}
