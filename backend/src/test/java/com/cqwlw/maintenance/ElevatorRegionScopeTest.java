package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ElevatorService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElevatorRegionScopeTest {

    private ElevatorMapper elevatorMapper;
    private WorkOrderMapper workOrderMapper;
    private EmployeeScopeService scopeService;
    private ElevatorService service;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 单测无 MyBatis 环境，LambdaQueryWrapper/LambdaUpdateWrapper 需 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Elevator.class);
        TableInfoHelper.initTableInfo(assistant, WorkOrder.class);
    }

    private void setUp(Employee emp) {
        elevatorMapper = mock(ElevatorMapper.class);
        workOrderMapper = mock(WorkOrderMapper.class);
        scopeService = mock(EmployeeScopeService.class);
        service = new ElevatorService(elevatorMapper, mock(UseUnitMapper.class), scopeService, workOrderMapper,
                mock(com.cqwlw.maintenance.mapper.CompanyMapper.class));
        when(scopeService.require("emp_1")).thenReturn(emp);
    }

    private static Employee worker(String regionId, String platformId) {
        Employee e = new Employee();
        e.id = "emp_1";
        e.regionId = regionId;
        e.platformId = platformId;
        return e;
    }

    private static Elevator pooledElevator(String id, String regionId) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = "CQ-" + id;
        el.elevatorName = "验证梯 " + id;
        el.regionId = regionId;
        return el;
    }

    /** 非管理角色读桩：visibleReadPlatformIds 非 null（区域集合口径，具体成员不再作为电梯匹配键） */
    private void stubNonManagementRead() {
        when(scopeService.visibleReadPlatformIds(any())).thenReturn(Set.of("pid_zhang"));
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(Set.of("pid_zhang"));
    }

    @Test
    void listViewPooledElevatorVisibleToSameRegionWorker() {
        setUp(worker("rg_1", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectList(any())).thenReturn(List.of(pooledElevator("el_pool", "rg_1")));

        List<Map<String, Object>> list = service.listView("emp_1");
        assertEquals(1, list.size(), "池化新梯（无绑定）对同区域维保员应可见");
        assertEquals("el_pool", list.get(0).get("id"));
    }

    @Test
    void listViewPooledElevatorHiddenFromOtherRegion() {
        setUp(worker("rg_2", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectList(any())).thenReturn(List.of(pooledElevator("el_pool", "rg_1")));

        assertTrue(service.listView("emp_1").isEmpty(), "其他区域维保员不可见 rg_1 电梯");
    }

    @Test
    void listViewUnrestrictedElevatorVisibleToAnyRegion() {
        setUp(worker("rg_2", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectList(any())).thenReturn(List.of(pooledElevator("el_open", null)));

        assertEquals(1, service.listView("emp_1").size(), "不限区域（regionId 空）电梯对任意区域可见");
    }

    /** 档案投影：local.regionName 经 RegionMapper 解析；梯级电话为空时回退使用单位档案，梯级有值优先 */
    @Test
    @SuppressWarnings("unchecked")
    void profileExposesRegionNameAndUnitPhoneFallback() {
        setUp(worker("rg_1", "pid_zhang"));
        stubNonManagementRead();
        UseUnitMapper useUnits = mock(UseUnitMapper.class);
        UseUnit uu = new UseUnit();
        uu.id = "uu_1";
        uu.unitName = "某物业";
        uu.elevatorAdministerPhone = "13900001111";
        uu.emergencyPhone = "023-12345678";
        when(useUnits.selectById("uu_1")).thenReturn(uu);
        com.cqwlw.maintenance.mapper.RegionMapper regions =
                mock(com.cqwlw.maintenance.mapper.RegionMapper.class);
        com.cqwlw.maintenance.entity.Region region = new com.cqwlw.maintenance.entity.Region();
        region.id = "rg_1";
        region.name = "渝北区";
        when(regions.selectById("rg_1")).thenReturn(region);
        ElevatorService svc = new ElevatorService(elevatorMapper, useUnits, scopeService, workOrderMapper,
                mock(com.cqwlw.maintenance.mapper.CompanyMapper.class));
        svc.setRegionMapper(regions);
        Elevator el = pooledElevator("el_p", "rg_1");
        el.useUnitId = "uu_1";
        when(elevatorMapper.selectById("el_p")).thenReturn(el);

        Map<String, Object> p = svc.profile("el_p", "emp_1");

        assertEquals("渝北区", ((Map<String, Object>) p.get("local")).get("regionName"));
        assertEquals("13900001111", ((Map<String, Object>) p.get("platform")).get("elevatorAdministerPhone"));
        assertEquals("023-12345678", ((Map<String, Object>) p.get("platform")).get("emergencyPhone"));

        el.emergencyPhone = "400-111";
        Map<String, Object> p2 = svc.profile("el_p", "emp_1");
        assertEquals("400-111", ((Map<String, Object>) p2.get("platform")).get("emergencyPhone"), "梯级已有值不得被单位档案覆盖");

        // overview 概览：四格/统计/联系人/服务记录区块常驻（可选依赖缺失时对应区块留空）
        Map<String, Object> overview = (Map<String, Object>) p.get("overview");
        org.junit.jupiter.api.Assertions.assertNotNull(overview, "档案投影必须带 overview 区块");
        assertEquals("某物业", overview.get("useUnitName"));
        org.junit.jupiter.api.Assertions.assertNotNull(overview.get("stats"));
        org.junit.jupiter.api.Assertions.assertNotNull(overview.get("contact"));
        assertEquals(List.of(), overview.get("serviceLog"));
    }

    @Test
    void profilePooledElevatorReadableForSameRegion() {
        setUp(worker("rg_1", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectById("el_pool")).thenReturn(pooledElevator("el_pool", "rg_1"));

        Map<String, Object> p = service.profile("el_pool", "emp_1");
        assertEquals("el_pool", p.get("elevatorId"), "同区域维保员可读池化新梯档案");
    }

    @Test
    void profilePooledElevatorDeniedForOtherRegion() {
        setUp(worker("rg_2", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectById("el_pool")).thenReturn(pooledElevator("el_pool", "rg_1"));

        BizException e = assertThrows(BizException.class, () -> service.profile("el_pool", "emp_1"));
        assertEquals(1403, e.getCode(), "跨区域读档案应 1403");
    }

    @Test
    void updateLocalClaimerWithOpenOrderAllowed() {
        setUp(worker("rg_1", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectById("el_pool")).thenReturn(pooledElevator("el_pool", "rg_1"));
        // 本人认领了该梯在途工单（status≠DONE 且 worker/assistant platformId 命中）
        when(workOrderMapper.selectCount(any())).thenReturn(1L);

        service.updateLocal("el_pool", "emp_1", Map.of("brand", "奥的斯"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Elevator>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(elevatorMapper).update(isNull(), cap.capture());
        assertTrue(cap.getValue().getSqlSet().contains("brand"), "认领人应可维护该梯本地信息");
    }

    @Test
    void updateLocalSameRegionWithoutOpenOrderDenied() {
        setUp(worker("rg_1", "pid_zhang"));
        stubNonManagementRead();
        when(elevatorMapper.selectById("el_pool")).thenReturn(pooledElevator("el_pool", "rg_1"));
        when(workOrderMapper.selectCount(any())).thenReturn(0L); // 同区域但无在途工单关系

        BizException e = assertThrows(BizException.class,
                () -> service.updateLocal("el_pool", "emp_1", Map.of("brand", "X")));
        assertEquals(1403, e.getCode(), "写不扩区域：同区域但非认领人/配合人应 1403");
        verify(elevatorMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateLocalManagementBypassAllowed() {
        setUp(worker(null, null));
        // 管理角色：visibleWorkerPlatformIds == null 旁路，无需在途工单
        when(scopeService.visibleWorkerPlatformIds(any())).thenReturn(null);
        when(elevatorMapper.selectById("el_pool")).thenReturn(pooledElevator("el_pool", "rg_1"));

        service.updateProjectName("el_pool", "emp_1", "世纪大厦");

        ArgumentCaptor<Elevator> cap = ArgumentCaptor.forClass(Elevator.class);
        verify(elevatorMapper).updateById(cap.capture());
        assertEquals("世纪大厦", cap.getValue().projectName);
    }
}
