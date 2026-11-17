package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门户令牌的作用域（docs/14 §4 部门域互斥）：使用单位账号只能打 /unit/** 与 /files/**，
 * 借门户令牌读取移交单、工单等员工端点必须 403。
 */
class UnitTokenScopeTest {

    private static AuthInterceptor interceptor(String subject, boolean unitToken) {
        JwtService jwt = mock(JwtService.class);
        Claims claims = new DefaultClaims(Map.of(
                "sub", subject,
                "role", unitToken ? "UNIT_ADMIN" : "WORKER",
                "ut", unitToken ? "1" : "0",
                "client", "mp",
                "sid", "s1"));
        when(jwt.verify("t")).thenReturn(claims);
        return new AuthInterceptor(jwt, mock(EmployeeMapper.class));
    }

    private static MockHttpServletRequest req(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.addHeader("Authorization", "Bearer t");
        return r;
    }

    /** 门户令牌读移交单列表 → 403（不能借令牌看全公司单据） */
    @Test
    void unitTokenCannotReadStaffEndpoints() {
        BizException ex = assertThrows(BizException.class,
                () -> interceptor("uu_1", true).preHandle(req("/handovers"), null, null));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("门户"));

        BizException work = assertThrows(BizException.class,
                () -> interceptor("uu_1", true).preHandle(req("/work-orders"), null, null));
        assertEquals(403, work.getCode());
    }

    /** 门户与上传接口放行，并写入 useUnitId/role */
    @Test
    void unitTokenAllowedOnPortalAndFiles() {
        MockHttpServletRequest portal = req("/unit/elevators");
        assertTrue(interceptor("uu_1", true).preHandle(portal, null, null));
        assertEquals("uu_1", portal.getAttribute(AuthInterceptor.ATTR_UNIT_ID));
        assertEquals("UNIT_ADMIN", portal.getAttribute(AuthInterceptor.ATTR_ROLE));

        assertTrue(interceptor("uu_1", true).preHandle(req("/files/upload"), null, null));
    }

    // ── M1：门户读端点改统一令牌 + CLIENT 本单位谓词 ──

    private com.cqwlw.maintenance.service.UnitRecordService recordService(
            com.cqwlw.maintenance.mapper.MaintainRecordMapper records,
            com.cqwlw.maintenance.mapper.ElevatorMapper elevators) {
        com.cqwlw.maintenance.service.WorkOrderService orders =
                mock(com.cqwlw.maintenance.service.WorkOrderService.class);
        when(orders.paginate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    java.util.List<?> list = inv.getArgument(0);
                    return Map.of("list", list, "total", list.size());
                });
        return new com.cqwlw.maintenance.service.UnitRecordService(records, orders,
                mock(com.cqwlw.maintenance.service.FileStorageService.class), elevators);
    }

    private com.cqwlw.maintenance.entity.MaintainRecord record(String id, String elevatorCode) {
        com.cqwlw.maintenance.entity.MaintainRecord r = new com.cqwlw.maintenance.entity.MaintainRecord();
        r.id = id;
        r.elevatorCode = elevatorCode;
        r.confirmStatus = "PENDING";
        // P1 T4：客户待签列表只认唯一可签状态（docs/14 §4.6），门禁前置后 fixture 须为可签记录
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        return r;
    }

    private void elevator(com.cqwlw.maintenance.mapper.ElevatorMapper elevators, String code, String unitId) {
        com.cqwlw.maintenance.entity.Elevator el = new com.cqwlw.maintenance.entity.Elevator();
        el.elevatorCode = code;
        el.useUnitId = unitId;
        when(elevators.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(el);
    }

    /** 员工令牌（无 CLIENT 角色）读门户记录 → 1403 */
    @Test
    void maintTokenCannotReadPortalRecords() {
        var records = mock(com.cqwlw.maintenance.mapper.MaintainRecordMapper.class);
        var elevators = mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class);
        BizException ex = assertThrows(BizException.class,
                () -> recordService(records, elevators).pendingAs(Map.of(), java.util.Set.of("MAINT_TECH"), "u1"));
        assertEquals(1403, ex.getCode());
    }

    /** 统一 CLIENT 令牌：pending 仅本单位记录 */
    @Test
    void clientPendingOnlyOwnUnit() {
        var records = mock(com.cqwlw.maintenance.mapper.MaintainRecordMapper.class);
        var elevators = mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class);
        when(records.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                record("r1", "DT-1"), record("r2", "DT-2")));
        // P2 T5（N+1 消除）：pendingAs 电梯归属改为一次批量 .in(elevatorCode) 查询（不再逐条 selectOne）；
        // DT-2 属另一单位 u2 → r2 被归属过滤
        when(elevators.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(elFor("DT-1", "u1"), elFor("DT-2", "u2")));
        var out = recordService(records, elevators).pendingAs(Map.of(), java.util.Set.of("CLIENT_ADMIN"), "u1");
        assertEquals(1, ((java.util.List<?>) out.get("list")).size());
    }

    private com.cqwlw.maintenance.entity.Elevator elFor(String code, String unitId) {
        com.cqwlw.maintenance.entity.Elevator el = new com.cqwlw.maintenance.entity.Elevator();
        el.elevatorCode = code;
        el.useUnitId = unitId;
        return el;
    }

    /** 跨单位读记录 → 1403 无副作用 */
    @Test
    void crossUnitGetForbidden() {
        var records = mock(com.cqwlw.maintenance.mapper.MaintainRecordMapper.class);
        var elevators = mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class);
        when(records.selectById("r1")).thenReturn(record("r1", "DT-1"));
        elevator(elevators, "DT-1", "u-other");
        BizException ex = assertThrows(BizException.class,
                () -> recordService(records, elevators).getAs("r1", java.util.Set.of("CLIENT_ADMIN"), "u1"));
        assertEquals(1403, ex.getCode());
    }

    /** 旧门户令牌（UNIT_ADMIN + 单位锚点）兜底仍可用 */
    @Test
    void legacyPortalRoleStillAllowed() {
        var records = mock(com.cqwlw.maintenance.mapper.MaintainRecordMapper.class);
        var elevators = mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class);
        when(records.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        var out = recordService(records, elevators).pendingAs(Map.of(), java.util.Set.of("UNIT_ADMIN"), "uu_1");
        assertEquals(0, ((java.util.List<?>) out.get("list")).size());
    }
}
