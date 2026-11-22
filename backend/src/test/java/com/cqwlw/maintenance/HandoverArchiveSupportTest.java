package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.HandoverArchiveSupport;
import com.cqwlw.maintenance.service.PlatformClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoverArchiveSupportTest {

    private UseUnitMapper useUnitMapper;
    private ElevatorMapper elevatorMapper;
    private PlatformClient platformClient;
    private HandoverArchiveSupport support;

    @BeforeEach
    void setUp() {
        useUnitMapper = mock(UseUnitMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        platformClient = mock(PlatformClient.class);
        support = new HandoverArchiveSupport(useUnitMapper, elevatorMapper, platformClient);
    }

    private static HandoverOrder order() {
        HandoverOrder o = new HandoverOrder();
        o.factoryNumber = "SGL-2026-001";
        return o;
    }

    private static Map<String, Object> platformRecord() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("elevatorCode", "130421");
        p.put("registrationCode", "REG-2026-001");
        p.put("deviceCode", "DEV-2026-001");
        p.put("factoryNumber", "SGL-2026-001");
        p.put("useUnitEntityId", "entity-1");
        p.put("elevatorAdminister", "安全管理员");
        p.put("elevatorAdministerPhone", "13800000001");
        p.put("emergencyPhone", "023-12345678");
        return p;
    }

    @Test
    void backfillsCodesAndContactsFromUniqueFactoryMatch() {
        HandoverOrder o = order();
        Elevator el = new Elevator();
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord()));

        support.enrich(o, el);

        assertEquals("130421", o.elevatorCode);
        assertEquals("REG-2026-001", o.regCode);
        assertEquals("DEV-2026-001", o.deviceCode);
        assertEquals("130421", el.elevatorCode);
        assertEquals("REG-2026-001", el.regCode);
        assertEquals("DEV-2026-001", el.deviceCode);
        assertEquals("entity-1", el.useUnitEntityId);
        assertEquals("安全管理员", el.elevatorAdminister);
        assertEquals("13800000001", el.elevatorAdministerPhone);
        assertEquals("023-12345678", el.emergencyPhone);
    }

    @Test
    void rejectsZeroOrMultipleMatchingRecords() {
        HandoverOrder o = order();
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of());
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));

        Map<String, Object> other = platformRecord();
        other.put("elevatorCode", "130422");
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord(), other));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
    }

    @Test
    void rejectsMixedRawResponsesEvenWhenOnlyOneMatches() {
        HandoverOrder o = order();
        Map<String, Object> unrelated = platformRecord();
        unrelated.put("factoryNumber", "OTHER-FACTORY");
        unrelated.put("registrationCode", "OTHER-REG");
        unrelated.put("deviceCode", "OTHER-DEV");
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord(), unrelated));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
    }

    @Test
    void factoryMismatchCannotBeOverriddenByRegistrationMatch() {
        HandoverOrder o = order();
        o.regCode = "REG-2026-001";
        Map<String, Object> p = platformRecord();
        p.put("factoryNumber", "OTHER-FACTORY");
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(p));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
    }

    @Test
    void duplicateRegistrationCodeRejectsArchive() {
        HandoverOrder o = order();
        o.elevatorCode = "130421";
        o.regCode = "REG-2026-001";
        o.deviceCode = "DEV-2026-001";
        when(elevatorMapper.selectCount(any())).thenReturn(0L, 1L);
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
        verify(platformClient, never()).queryElevatorInfo(any());
    }

    @Test
    void duplicateDeviceCodeRejectsArchive() {
        HandoverOrder o = order();
        o.elevatorCode = "130421";
        o.regCode = "REG-2026-001";
        o.deviceCode = "DEV-2026-001";
        when(elevatorMapper.selectCount(any())).thenReturn(0L, 0L, 1L);
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
        verify(platformClient, never()).queryElevatorInfo(any());
    }

    @Test
    void rejectsForeignOrIncompleteRecord() {
        HandoverOrder o = order();
        Map<String, Object> foreign = platformRecord();
        foreign.put("factoryNumber", "OTHER-FACTORY");
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(foreign));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));

        Map<String, Object> incomplete = platformRecord();
        incomplete.remove("registrationCode");
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(incomplete));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
    }

    @Test
    void rejectsConflictBetweenHandoverAndPlatform() {
        HandoverOrder o = order();
        o.elevatorCode = "DIFFERENT-CODE";
        when(platformClient.queryElevatorInfo(any())).thenReturn(List.of(platformRecord()));
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
    }

    @Test
    void completeCodesSkipQueryButRejectDuplicateLocalCode() {
        HandoverOrder o = order();
        o.elevatorCode = "130421";
        o.regCode = "REG-2026-001";
        o.deviceCode = "DEV-2026-001";
        when(elevatorMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
        verify(platformClient, never()).queryElevatorInfo(any());
    }

    @Test
    void completeCodesSkipQueryAndAcceptUniqueLocalCode() {
        HandoverOrder o = order();
        o.elevatorCode = "130421";
        o.regCode = "REG-2026-001";
        o.deviceCode = "DEV-2026-001";
        when(elevatorMapper.selectCount(any())).thenReturn(0L);

        support.enrich(o, new Elevator());

        verify(platformClient, never()).queryElevatorInfo(any());
    }

    @Test
    void missingFactoryNumberAlwaysRejects() {
        HandoverOrder o = order();
        o.factoryNumber = null;
        o.elevatorCode = "130421";
        o.regCode = "REG-2026-001";
        o.deviceCode = "DEV-2026-001";
        assertThrows(BizException.class, () -> support.enrich(o, new Elevator()));
        verify(platformClient, never()).queryElevatorInfo(any());
    }

    @Test
    void optionsContainOnlyUseUnitIdAndName() {
        UseUnit u = new UseUnit();
        u.id = "uu_1";
        u.unitName = "世纪物业";
        u.unitPrincipal = "不应下发";
        when(useUnitMapper.selectList(any())).thenReturn(List.of(u));
        assertEquals(List.of(Map.of("id", "uu_1", "unitName", "世纪物业")), support.useUnitOptions());
    }
}
