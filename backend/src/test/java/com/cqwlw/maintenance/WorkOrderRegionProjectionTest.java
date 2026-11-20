package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单视图区域快照透出（2026-11-20 区域抢单池设计 §4.4，Task 10 结转①）：
 * 管理端工单池「待接单 · 区域名」与小程序池卡片依赖 toMap 投影 regionId（M4 快照原值）
 * + regionName（经 RegionMapper 解析，口径同 AdminArchiveService.regionNameOf：
 * 空/查不到/未注入 mapper 回空串，不抛错）。
 */
class WorkOrderRegionProjectionTest {

    private RegionMapper regionMapper;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        regionMapper = mock(RegionMapper.class);
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        service = new WorkOrderService(mock(WorkOrderMapper.class), mock(ElevatorMapper.class),
                mock(UseUnitMapper.class), mock(EmployeeMapper.class), mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class), mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), mock(EmployeeScopeService.class),
                mock(CheckinThresholdService.class), mock(FileStorageService.class), TxTestSupport.noopTx(), props);
        service.setRegionMapper(regionMapper);
    }

    private static WorkOrder order(String regionId) {
        WorkOrder o = new WorkOrder();
        o.id = "wo_r1";
        o.orderNo = "WB20261121001";
        o.elevatorId = "el_1";
        o.status = "PENDING";
        o.regionId = regionId;
        return o;
    }

    @Test
    void toMapProjectsRegionIdAndResolvedName() {
        Region r = new Region();
        r.id = "rg_yubei";
        r.name = "渝北区";
        when(regionMapper.selectById("rg_yubei")).thenReturn(r);

        Map<String, Object> m = service.toMap(order("rg_yubei"), false);

        assertEquals("rg_yubei", m.get("regionId"), "regionId 为派单时电梯区域快照原值（M4）");
        assertEquals("渝北区", m.get("regionName"), "管理端「待接单 · 区域名」展示依赖该区域名");
    }

    @Test
    void toMapRegionEmptyWhenOrderUnassignedRegion() {
        Map<String, Object> m = service.toMap(order(null), false);

        assertEquals("", m.get("regionId"), "不限区域工单 regionId 投影空串");
        assertEquals("", m.get("regionName"), "不限区域工单 regionName 回空串（前端显示「不限区域」）");
    }

    @Test
    void toMapRegionNameEmptyWhenRegionRowMissing() {
        when(regionMapper.selectById("rg_gone")).thenReturn(null);

        Map<String, Object> m = service.toMap(order("rg_gone"), false);

        assertEquals("rg_gone", m.get("regionId"));
        assertEquals("", m.get("regionName"), "区域主数据查不到时名称回空串，不抛错");
    }

    /** 未注入 RegionMapper（旧测试构造器口径）时同样回空串，不影响既有调用方 */
    @Test
    void toMapRegionNameEmptyWithoutRegionMapper() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-0123456789-abcdefghijk");
        WorkOrderService bare = new WorkOrderService(mock(WorkOrderMapper.class), mock(ElevatorMapper.class),
                mock(UseUnitMapper.class), mock(EmployeeMapper.class), mock(CompanyMapper.class),
                mock(MaintainRecordMapper.class), mock(FaultMapper.class), mock(InspectRecordMapper.class),
                mock(HandoverOrderMapper.class), mock(MessageMapper.class), mock(ChecklistService.class),
                mock(PlatformReportService.class), mock(EmployeeScopeService.class),
                mock(CheckinThresholdService.class), mock(FileStorageService.class), TxTestSupport.noopTx(), props);

        Map<String, Object> m = bare.toMap(order("rg_yubei"), false);

        assertEquals("rg_yubei", m.get("regionId"));
        assertEquals("", m.get("regionName"));
    }
}
