package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ElevatorService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 列表投影必须包含 nextMaintenanceDate 和 maintUnitName。 */
class ElevatorListProjectionTest {

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 单测无 MyBatis 环境，LambdaQueryWrapper 需 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Elevator.class);
    }

    private ElevatorService service(ElevatorMapper elevatorMapper, CompanyMapper companyMapper) {
        EmployeeScopeService scopeService = mock(EmployeeScopeService.class);
        Employee e = new Employee();
        e.id = "emp_1";
        when(scopeService.require("emp_1")).thenReturn(e);
        // 管理角色旁路（ids==null 不限区域），投影断言不受读谓词干扰
        when(scopeService.visibleReadPlatformIds(any())).thenReturn(null);
        return new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService,
                mock(WorkOrderMapper.class), companyMapper);
    }

    private static Elevator elevator(LocalDate nextMaintenanceDate) {
        Elevator el = new Elevator();
        el.id = "el_1";
        el.elevatorCode = "EM-2024-001";
        el.elevatorName = "世纪大厦 1# 客梯";
        el.nextMaintenanceDate = nextMaintenanceDate;
        return el;
    }

    private static Company company(String name) {
        Company c = new Company();
        c.id = "comp_1";
        c.name = name;
        return c;
    }

    @Test
    void listRowCarriesNextMaintenanceDateAndMaintUnitName() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator(LocalDate.of(2026, 12, 5))));
        when(companyMapper.selectList(null)).thenReturn(List.of(company("博威电梯")));

        List<Map<String, Object>> list = service(elevatorMapper, companyMapper).listView("emp_1");

        assertEquals(1, list.size());
        assertEquals("2026-12-05", list.get(0).get("nextMaintenanceDate"), "下次维保日期应格式化为 yyyy-MM-dd");
        assertEquals("博威电梯", list.get(0).get("maintUnitName"), "维保单位名应取 company 表首条");
    }

    @Test
    void listRowDefaultsWhenDateOrCompanyMissing() {
        ElevatorMapper elevatorMapper = mock(ElevatorMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        when(elevatorMapper.selectList(any())).thenReturn(List.of(elevator(null)));
        when(companyMapper.selectList(null)).thenReturn(List.of());

        Map<String, Object> row = service(elevatorMapper, companyMapper).listView("emp_1").get(0);

        assertEquals("", row.get("nextMaintenanceDate"), "未排期时应为空串而非 null");
        assertEquals("", row.get("maintUnitName"), "未配置维保单位时应为空串而非 null");
    }
}
