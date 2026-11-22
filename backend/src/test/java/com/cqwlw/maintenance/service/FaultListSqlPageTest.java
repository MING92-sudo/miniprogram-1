package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaultListSqlPageTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Fault.class);
    }

    private FaultMapper faults;
    private ElevatorMapper elevators;
    private EmployeeMapper employees;
    private UseUnitMapper units;
    private CompanyMapper companies;
    private EmployeeScopeService scope;
    private DirectoryService service;

    @BeforeEach
    void setUp() {
        faults = mock(FaultMapper.class);
        elevators = mock(ElevatorMapper.class);
        employees = mock(EmployeeMapper.class);
        units = mock(UseUnitMapper.class);
        companies = mock(CompanyMapper.class);
        scope = mock(EmployeeScopeService.class);
        service = new DirectoryService(mock(RescueMapper.class), faults, mock(DrillMapper.class),
                mock(InspectRecordMapper.class), mock(MessageMapper.class), mock(KnowledgeMapper.class),
                elevators, mock(ChecklistService.class), mock(WorkOrderService.class), scope, employees, units,
                mock(WorkOrderMapper.class), companies, mock(FileStorageService.class), mock(TenantSnapshotService.class));
        when(elevators.selectList(any())).thenReturn(List.of());
        when(employees.selectList(any())).thenReturn(List.of());
        when(companies.selectList(any())).thenReturn(List.of());
    }

    @Test
    void usesSqlPageAndConstantQueryCountForCurrentPageEnrichment() {
        Employee admin = new Employee();
        admin.id = "boss";
        admin.role = "ADMIN";
        when(scope.require("boss")).thenReturn(admin);
        when(scope.visibleCreatorIds(admin)).thenReturn(null);
        Fault first = fault("f1", "OPEN");
        Fault second = fault("f2", "OPEN");
        Page<Fault> page = new Page<>(2, 2);
        page.setRecords(List.of(first, second));
        page.setTotal(7);
        when(faults.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> out = service.listFaults(Map.of("page", "2", "size", "2", "status", "", "trapped", ""), "boss");

        assertEquals(7, out.get("total"));
        assertEquals(2, ((List<?>) out.get("list")).size());
        verify(faults).selectPage(any(), any());
        verify(faults, never()).selectList(any());
        verify(elevators, times(1)).selectList(any());
        verify(employees, times(1)).selectList(any());
        verify(companies, times(1)).selectList(any());
    }

    @Test
    void emptyPageDoesNotEnrich() {
        Employee admin = new Employee();
        admin.id = "boss";
        admin.role = "ADMIN";
        when(scope.require("boss")).thenReturn(admin);
        when(scope.visibleCreatorIds(admin)).thenReturn(null);
        Page<Fault> page = new Page<>(3, 20);
        page.setRecords(List.of());
        page.setTotal(2);
        when(faults.selectPage(any(), any())).thenReturn(page);

        service.listFaults(Map.of("page", "3", "status", "", "trapped", ""), "boss");

        verify(elevators, never()).selectList(any());
        verify(companies, never()).selectList(any());
    }

    @Test
    void restrictedOpenQueryContainsRegionNotExistsAndTrappedPredicate() {
        Employee worker = new Employee();
        worker.id = "e1";
        worker.role = "MAINT_TECH";
        worker.regionId = "r1";
        when(scope.require("e1")).thenReturn(worker);
        when(scope.visibleCreatorIds(worker)).thenReturn(Set.of("e1"));
        Page<Fault> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(faults.selectPage(any(), any())).thenReturn(page);

        service.listFaults(Map.of("status", "OPEN", "trapped", "1"), "e1");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Fault>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(faults).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("NOT EXISTS"), sql);
        assertTrue(sql.contains("trapped_count"), sql);
    }

    private static Fault fault(String id, String status) {
        Fault f = new Fault();
        f.id = id;
        f.status = status;
        f.elevatorCode = "EM-1";
        return f;
    }
}
