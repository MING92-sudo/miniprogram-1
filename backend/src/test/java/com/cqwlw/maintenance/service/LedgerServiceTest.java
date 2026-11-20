package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.SysInvite;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.BizProjectMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.SysInviteMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M3 台账域业务规则：组织单级/电梯双人确认/邀请有效期。 */
class LedgerServiceTest {

    private final SysOrgMapper orgMapper = mock(SysOrgMapper.class);
    private final BizProjectMapper projectMapper = mock(BizProjectMapper.class);
    private final SysInviteMapper inviteMapper = mock(SysInviteMapper.class);
    private final BizElevatorChangeMapper changeMapper = mock(BizElevatorChangeMapper.class);
    private final ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final LedgerService service = new LedgerService(orgMapper, projectMapper, inviteMapper, changeMapper, elevatorMapper, audit);

    @Test
    void unitOrgCannotHaveChildren() {
        SysOrg unit = new SysOrg();
        unit.id = "u1";
        unit.type = "UNIT";
        when(orgMapper.selectById("u1")).thenReturn(unit);
        BizException e = assertThrows(BizException.class, () ->
                service.createOrg(Map.of("type", "TEAM", "name", "x", "parentId", "u1"), "admin"));
        assertEquals(422, e.getCode());
    }

    @Test
    void createOrgUnderDeptOk() {
        SysOrg dept = new SysOrg();
        dept.id = "d1";
        dept.type = "DEPT";
        when(orgMapper.selectById("d1")).thenReturn(dept);
        SysOrg org = service.createOrg(Map.of("type", "TEAM", "name", "班组", "parentId", "d1"), "admin");
        assertEquals("TEAM", org.type);
        verify(orgMapper).insert(any(SysOrg.class));
    }

    // ── 层级口径（V51 同类错误的防回归）：公司 → 部门 → 班组，且单公司 ──

    @Test
    void deptMustHangUnderCompany() {
        BizException noParent = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "DEPT", "name", "维保部"), "admin"));
        assertEquals(422, noParent.getCode());

        SysOrg dept = new SysOrg();
        dept.id = "d1";
        dept.type = "DEPT";
        when(orgMapper.selectById("d1")).thenReturn(dept);
        BizException nested = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "DEPT", "name", "维保部", "parentId", "d1"), "admin"));
        assertEquals(422, nested.getCode());
        assertTrue(nested.getMessage().contains("公司"));
    }

    @Test
    void teamMustHangUnderDept() {
        SysOrg company = new SysOrg();
        company.id = "c1";
        company.type = "COMPANY";
        when(orgMapper.selectById("c1")).thenReturn(company);
        BizException e = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "TEAM", "name", "维保一班", "parentId", "c1"), "admin"));
        assertEquals(422, e.getCode());
    }

    @Test
    void companyMustBeSingleRoot() {
        when(orgMapper.selectCount(any())).thenReturn(1L);
        BizException dup = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "COMPANY", "name", "第二家公司"), "admin"));
        assertEquals(422, dup.getCode());

        when(orgMapper.selectCount(any())).thenReturn(0L);
        SysOrg ok = service.createOrg(Map.of("type", "COMPANY", "name", "重庆博威电梯有限公司"), "admin");
        assertEquals("COMPANY", ok.type);
    }

    @Test
    void companyCannotHaveParent() {
        SysOrg company = new SysOrg();
        company.id = "c1";
        company.type = "COMPANY";
        when(orgMapper.selectById("c1")).thenReturn(company);
        BizException e = assertThrows(BizException.class,
                () -> service.createOrg(Map.of("type", "COMPANY", "name", "某公司", "parentId", "c1"), "admin"));
        assertEquals(422, e.getCode());
    }

    @Test
    void projectOrgMustBeDept() {
        SysOrg company = new SysOrg();
        company.id = "c1";
        company.type = "COMPANY";
        when(orgMapper.selectById("c1")).thenReturn(company);
        BizException e = assertThrows(BizException.class, () -> service.createProject(
                Map.of("projectNo", "P1", "name", "项目", "maintOrgId", "c1"), "admin"));
        assertEquals(422, e.getCode());
    }

    @Test
    void confirmChangeRejectsSamePerson() {
        BizElevatorChange c = new BizElevatorChange();
        c.id = "c1";
        c.initiatorId = "e1";
        c.status = "PENDING";
        c.changeType = "DISABLE";
        when(changeMapper.selectById("c1")).thenReturn(c);
        BizException e = assertThrows(BizException.class, () -> service.confirmChange("c1", "e1", "e1"));
        assertEquals(422, e.getCode());
    }

    @Test
    void confirmChangeAppliesElevatorStatus() {
        BizElevatorChange c = new BizElevatorChange();
        c.id = "c1";
        c.elevatorId = "el1";
        c.initiatorId = "e1";
        c.status = "PENDING";
        c.changeType = "SCRAP";
        when(changeMapper.selectById("c1")).thenReturn(c);
        Elevator el = new Elevator();
        el.id = "el1";
        el.status = "ACTIVE";
        when(elevatorMapper.selectById("el1")).thenReturn(el);
        BizElevatorChange done = service.confirmChange("c1", "e2", "e2");
        assertEquals("CONFIRMED", done.status);
        assertEquals("SCRAPPED", el.status);
        verify(elevatorMapper).updateById(el);
    }

    @Test
    void inviteValidityRange() {
        assertThrows(BizException.class, () -> service.createInvite(Map.of("validDays", 0), "admin"));
        assertThrows(BizException.class, () -> service.createInvite(Map.of("validDays", 31), "admin"));
        SysInvite inv = service.createInvite(Map.of("validDays", 7), "admin");
        assertEquals("ACTIVE", inv.status);
        assertEquals(7, inv.validDays);
    }

    @Test
    void withdrawOnlyActive() {
        SysInvite inv = new SysInvite();
        inv.id = "i1";
        inv.status = "WITHDRAWN";
        when(inviteMapper.selectById("i1")).thenReturn(inv);
        assertThrows(BizException.class, () -> service.withdrawInvite("i1", "admin"));
    }
}