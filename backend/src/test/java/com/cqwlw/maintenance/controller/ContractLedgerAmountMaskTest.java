package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.mapper.MaintenanceContractMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractLedgerAmountMaskTest {

    private final MaintenanceContractMapper contractMapper = mock(MaintenanceContractMapper.class);

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintenanceContract.class);
    }

    private HttpServletRequest request(Set<String> roles) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLES)).thenReturn(roles);
        return req;
    }

    private MaintenanceContract contract() {
        MaintenanceContract c = new MaintenanceContract();
        c.id = "ct_1";
        c.contractNo = "HT-2026-01";
        c.amount = new BigDecimal("120000");
        c.startDate = LocalDate.of(2026, 1, 1);
        c.endDate = LocalDate.of(2027, 1, 1);
        c.status = "ACTIVE";
        return c;
    }

    /** MAINT_SUP（contract:view=C 仅期与台量）+ 异常混合 CLIENT_ADMIN → 金额必须掩码（修复前被 FULL_AMOUNT 放全量） */
    @Test
    void clientAdminNoLongerUnmasksAmount() {
        when(contractMapper.selectList(any())).thenReturn(List.of(contract()));
        List<Map<String, Object>> rows = new ContractLedgerController(contractMapper)
                .list(request(Set.of("MAINT_SUP", "CLIENT_ADMIN"))).getData();
        assertEquals(Boolean.TRUE, rows.get(0).get("amountMasked"));
        assertNull(rows.get(0).get("amount"));
    }

    /** 经理/平台完整金额口径逐条不变 */
    @Test
    void maintMgrKeepsFullAmount() {
        when(contractMapper.selectList(any())).thenReturn(List.of(contract()));
        List<Map<String, Object>> rows = new ContractLedgerController(contractMapper)
                .list(request(Set.of("MAINT_MGR"))).getData();
        assertEquals(Boolean.FALSE, rows.get(0).get("amountMasked"));
        assertEquals(new BigDecimal("120000"), rows.get(0).get("amount"));
    }
}
