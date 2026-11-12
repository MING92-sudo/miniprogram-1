package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import com.cqwlw.maintenance.service.RegionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 区域主数据（Task 1）：region 表 CRUD + 电梯/员工 region_id 分配。
 * 区域为移交/外接建档/使用单位门户的可见性范围谓词（后续任务消费）。
 */
class RegionServiceTest {

    private static RegionService service(RegionMapper regionMapper, ElevatorMapper elevatorMapper,
                                         EmployeeMapper employeeMapper) {
        return new RegionService(regionMapper, elevatorMapper, employeeMapper);
    }

    @Test
    void listCreatesAndFilters() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        RegionService svc = service(regionMapper, mock(ElevatorMapper.class), mock(EmployeeMapper.class));

        Region created = svc.create(Map.of("name", " 渝北区 ", "sortOrder", 1, "remark", "北区"));

        when(regionMapper.selectList(any())).thenReturn(List.of(created));
        List<Region> list = svc.list();
        assertEquals(1, list.size(), "建区域后列表应能查到");
        assertEquals("渝北区", list.get(0).name, "区域名称应去除首尾空白后落库");
    }

    @Test
    void createRejectsBlankName() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        RegionService svc = service(regionMapper, mock(ElevatorMapper.class), mock(EmployeeMapper.class));

        BizException e = assertThrows(BizException.class, () -> svc.create(Map.of("name", "   ")));
        assertEquals(422, e.getCode(), "区域名称留空应拒绝");
        verify(regionMapper, never()).insert(any(Region.class));
    }

    @Test
    void createRejectsNonNumericSortOrder() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        RegionService svc = service(regionMapper, mock(ElevatorMapper.class), mock(EmployeeMapper.class));

        BizException e = assertThrows(BizException.class,
                () -> svc.create(Map.of("name", "渝北区", "sortOrder", "abc")));
        assertEquals(422, e.getCode(), "排序号非数字应拒绝");
        verify(regionMapper, never()).insert(any(Region.class));
    }

    @Test
    void assignElevatorRegionSetsRegionId() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        RegionService svc = service(regionMapper, elevatorMapper, mock(EmployeeMapper.class));

        when(elevatorMapper.selectById("el_1")).thenReturn(new Elevator());
        Region region = new Region();
        region.id = "rg_1";
        region.name = "渝北区";
        when(regionMapper.selectById("rg_1")).thenReturn(region);

        svc.assignElevatorRegion("el_1", " rg_1 ");

        ArgumentCaptor<Elevator> cap = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).updateById(cap.capture());
        assertEquals("el_1", cap.getValue().id);
        assertEquals("rg_1", cap.getValue().regionId, "电梯应被分配到指定区域");
    }

    @Test
    void assignElevatorRegionRejectsMissingRegion() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        RegionService svc = service(regionMapper, elevatorMapper, mock(EmployeeMapper.class));

        when(elevatorMapper.selectById("el_1")).thenReturn(new Elevator());
        when(regionMapper.selectById("rg_missing")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> svc.assignElevatorRegion("el_1", "rg_missing"));
        assertEquals(1404, e.getCode(), "区域不存在应拒绝分配");
        verify(elevatorMapper, never()).updateById(any(Elevator.class));
    }

    @Test
    void assignEmployeeRegionRejectsMissingEmployee() {
        RegionMapper regionMapper = mock(RegionMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        RegionService svc = service(regionMapper, mock(ElevatorMapper.class), employeeMapper);

        when(employeeMapper.selectById("emp_x")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> svc.assignEmployeeRegion("emp_x", "rg_1"));
        assertEquals(1404, e.getCode(), "员工不存在应拒绝分配");
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }
}
