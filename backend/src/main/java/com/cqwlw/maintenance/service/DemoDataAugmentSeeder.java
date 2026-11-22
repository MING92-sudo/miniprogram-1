package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.BizExportApproval;
import com.cqwlw.maintenance.entity.BizProject;
import com.cqwlw.maintenance.entity.ContractElevator;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.HandoverCheck;
import com.cqwlw.maintenance.entity.HandoverCheckItem;
import com.cqwlw.maintenance.entity.HandoverOrder;
import com.cqwlw.maintenance.entity.HandoverRectification;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.entity.SysInvite;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.BizExportApprovalMapper;
import com.cqwlw.maintenance.mapper.BizProjectMapper;
import com.cqwlw.maintenance.mapper.ContractElevatorMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckItemMapper;
import com.cqwlw.maintenance.mapper.HandoverCheckMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.HandoverRectificationMapper;
import com.cqwlw.maintenance.mapper.MaintenanceContractMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.mapper.SysInviteMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示数据补全（仅 SEED_DEMO_DATA=true）：在基础种子之外，按域幂等补全管理端各页演示数据
 * （区域/项目/合同/移交含 74 项清单+整改+归档/邀请码/限时授权/敏感导出审批）。
 * 每域 selectCount==0 才写入，引用现有电梯/使用单位/账号，不覆盖已有数据、不清库。
 * 基础 DemoDataSeeder 先跑（@Order(0)），本补全后跑（@Order(1)）；生产置 SEED_DEMO_DATA=false。
 */
@Component
@Order(1)
public class DemoDataAugmentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataAugmentSeeder.class);

    private final AppProperties props;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final RegionMapper regionMapper;
    private final BizProjectMapper projectMapper;
    private final MaintenanceContractMapper contractMapper;
    private final ContractElevatorMapper contractElevatorMapper;
    private final HandoverOrderMapper handoverOrderMapper;
    private final HandoverCheckMapper handoverCheckMapper;
    private final HandoverCheckItemMapper handoverCheckItemMapper;
    private final HandoverRectificationMapper handoverRectificationMapper;
    private final SysInviteMapper inviteMapper;
    private final SysGrantMapper grantMapper;
    private final BizExportApprovalMapper exportApprovalMapper;
    private final HandoverChecklistService checklistService;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DemoDataAugmentSeeder(AppProperties props, ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper,
                                 EmployeeMapper employeeMapper, RegionMapper regionMapper, BizProjectMapper projectMapper,
                                 MaintenanceContractMapper contractMapper, ContractElevatorMapper contractElevatorMapper,
                                 HandoverOrderMapper handoverOrderMapper, HandoverCheckMapper handoverCheckMapper,
                                 HandoverCheckItemMapper handoverCheckItemMapper, HandoverRectificationMapper handoverRectificationMapper,
                                 SysInviteMapper inviteMapper, SysGrantMapper grantMapper,
                                 BizExportApprovalMapper exportApprovalMapper, HandoverChecklistService checklistService) {
        this.props = props;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.regionMapper = regionMapper;
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.contractElevatorMapper = contractElevatorMapper;
        this.handoverOrderMapper = handoverOrderMapper;
        this.handoverCheckMapper = handoverCheckMapper;
        this.handoverCheckItemMapper = handoverCheckItemMapper;
        this.handoverRectificationMapper = handoverRectificationMapper;
        this.inviteMapper = inviteMapper;
        this.grantMapper = grantMapper;
        this.exportApprovalMapper = exportApprovalMapper;
        this.checklistService = checklistService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isSeedDemoData()) {
            return;
        }
        Elevator el = first(elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>().last("LIMIT 1")));
        UseUnit uu = first(useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>().last("LIMIT 1")));
        Employee admin = first(employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getRole, "ADMIN", "SYS_ADMIN").last("LIMIT 1")));
        if (el == null || uu == null || admin == null) {
            return; // 基础种子尚未就绪，跳过（不阻断启动）
        }
        log.info("SEED_DEMO_DATA=true：按域幂等补全管理端演示数据（不清库、不覆盖已有数据）");
        String contractId = safely("合同", () -> seedContracts(uu, el));
        safely("区域", this::seedRegions);
        safely("项目", () -> seedProjects(uu, contractId, el));
        safely("移交", () -> seedHandover(el, admin));
        safely("邀请码", () -> seedInvites(admin));
        safely("限时授权", () -> seedGrants(admin));
        safely("敏感导出审批", () -> seedExportApprovals(admin));
        log.info("管理端演示数据补全完成");
    }

    /** 单域补全失败仅记日志，不阻断其余域与启动。 */
    private void safely(String domain, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("演示数据补全[{}]失败（跳过，不影响其余域）", domain, e);
        }
    }

    private String safely(String domain, java.util.function.Supplier<String> task) {
        try {
            return task.get();
        } catch (Exception e) {
            log.warn("演示数据补全[{}]失败（跳过，不影响其余域）", domain, e);
            return null;
        }
    }

    private static <T> T first(List<T> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private boolean empty(long count) {
        return count == 0;
    }

    private void seedRegions() {
        if (!empty(regionMapper.selectCount(null))) {
            return;
        }
        String[][] rows = {{"渝北区", "1"}, {"江北区", "2"}, {"南岸区", "3"}, {"沙坪坝区", "4"}};
        for (String[] r : rows) {
            Region region = new Region();
            region.id = Ids.next("rg");
            region.name = r[0];
            region.sortOrder = Integer.parseInt(r[1]);
            region.remark = "演示区域";
            regionMapper.insert(region);
        }
    }

    private String seedContracts(UseUnit uu, Elevator el) {
        MaintenanceContract existing = first(contractMapper.selectList(new LambdaQueryWrapper<MaintenanceContract>().last("LIMIT 1")));
        if (existing != null) {
            return existing.id;
        }
        MaintenanceContract c = new MaintenanceContract();
        c.id = Ids.next("ct");
        c.contractNo = "HT-2026-0001";
        c.useUnitId = uu.id;
        c.startDate = LocalDate.now().minusMonths(6);
        c.endDate = LocalDate.now().plusMonths(6);
        c.status = "ACTIVE";
        c.amount = new BigDecimal("36000.00");
        contractMapper.insert(c);
        ContractElevator ce = new ContractElevator();
        ce.id = Ids.next("ce");
        ce.contractId = c.id;
        ce.elevatorId = el.id;
        contractElevatorMapper.insert(ce);
        return c.id;
    }

    private void seedProjects(UseUnit uu, String contractId, Elevator el) {
        if (!empty(projectMapper.selectCount(null))) {
            return;
        }
        BizProject p = new BizProject();
        p.id = Ids.next("pj");
        p.projectNo = "XM-2026-0001";
        p.name = el.projectName != null ? el.projectName : "世纪大厦";
        p.useUnitId = uu.id;
        p.elevatorCount = 1;
        p.contractId = contractId;
        p.contractEndDate = LocalDate.now().plusMonths(6).toString();
        p.status = "ACTIVE";
        p.lastWorkDate = LocalDate.now().minusDays(3).toString();
        p.createdAt = LocalDateTime.now().minusMonths(6);
        projectMapper.insert(p);
    }

    private void seedHandover(Elevator el, Employee admin) {
        if (handoverCheckItemMapper.selectCount(null) > 0) {
            return; // 已有完整清单
        }
        // 清理上次失败遗留的半成品演示移交（仅演示单号），再整体重建
        HandoverOrder partial = first(handoverOrderMapper.selectList(
                new LambdaQueryWrapper<HandoverOrder>().eq(HandoverOrder::getHandoverNo, "YJ-2026-0001").last("LIMIT 1")));
        if (partial != null) {
            handoverCheckMapper.delete(new LambdaQueryWrapper<HandoverCheck>().eq(HandoverCheck::getHandoverId, partial.id));
            handoverRectificationMapper.delete(new LambdaQueryWrapper<HandoverRectification>().eq(HandoverRectification::getHandoverId, partial.id));
            handoverOrderMapper.deleteById(partial.id);
        }
        HandoverOrder ho = new HandoverOrder();
        ho.id = Ids.next("ho");
        ho.handoverNo = "YJ-2026-0001";
        ho.createdAt = LocalDateTime.now().minusDays(10);
        ho.status = "RECTIFYING";
        ho.projectName = el.projectName != null ? el.projectName : "演示项目";
        ho.elevatorCode = el.elevatorCode;
        ho.elevatorName = el.elevatorName != null ? el.elevatorName : "演示电梯";
        ho.category = el.category != null ? el.category : "曳引与强制驱动电梯";
        ho.location = el.location;
        ho.installCompleteDate = LocalDate.now().minusDays(20).toString();
        ho.installUnitName = "重庆博威电梯有限公司";
        ho.initiatorId = admin.id;
        ho.initiatorName = admin.name;
        ho.receiverId = admin.id;
        ho.receiverName = admin.name;
        handoverOrderMapper.insert(ho);

        HandoverCheck check = new HandoverCheck();
        check.id = Ids.next("hc");
        check.handoverId = ho.id;
        check.checkerId = admin.id;
        check.checkerName = admin.name;
        check.checkedAt = LocalDateTime.now().minusDays(9);
        check.totalItems = 74;
        check.passCount = 73;
        check.failCount = 1;
        check.naCount = 0;
        check.importantFailCount = 0;
        check.conclusion = "RECTIFY";
        check.snapshotJson = "{\"conclusion\":\"RECTIFY\",\"demo\":true}";
        handoverCheckMapper.insert(check);

        // 74 项清单：仅第 1 项不合格（一般项目），其余合格
        for (HandoverChecklistTemplate.Item t : HandoverChecklistTemplate.items()) {
            HandoverCheckItem it = new HandoverCheckItem();
            it.id = Ids.next("hi");
            it.checkId = check.id;
            it.itemNo = t.no();
            it.itemGroup = t.group();
            it.itemType = t.group(); // 兼容列 item_type NOT NULL（V27）
            it.clauseNo = t.clause();
            it.itemName = t.name();
            it.requirement = t.requirement();
            it.important = t.important();
            if (t.no() == 1) {
                it.result = "FAIL";
                it.failDesc = "演示：机房通道照明不足";
                it.photoFileIds = "";
            } else {
                it.result = "PASS";
                if (HandoverChecklistTemplate.isTestItem(t.no())) {
                    it.measuredValue = "1.0";
                }
            }
            handoverCheckItemMapper.insert(it);
        }

        HandoverRectification rect = new HandoverRectification();
        rect.id = Ids.next("hr");
        rect.handoverId = ho.id;
        rect.itemNo = 1;
        rect.itemType = "G01";
        rect.respParty = "安装单位";
        rect.dueDate = LocalDate.now().plusDays(7).toString();
        rect.source = "INSPECT";
        rect.voided = false;
        rect.rectMeasure = "演示：已增设机房照明";
        rect.rectifierName = admin.name;
        rect.rectifiedAt = LocalDateTime.now().minusDays(2);
        handoverRectificationMapper.insert(rect);

        checklistService.archive(ho.id); // 生成一梯一档 7 条目录 + SHA-256
    }

    private void seedInvites(Employee admin) {
        if (!empty(inviteMapper.selectCount(null))) {
            return;
        }
        SysInvite inv = new SysInvite();
        inv.id = Ids.next("iv");
        inv.code = "DEMO-INVITE-0001";
        inv.type = "INVITE";
        inv.createdBy = admin.id;
        inv.validDays = 30;
        inv.expiresAt = LocalDateTime.now().plusDays(30);
        inv.status = "ACTIVE";
        inv.joinedCount = 0;
        inv.createdAt = LocalDateTime.now().minusDays(1);
        inviteMapper.insert(inv);
    }

    private void seedGrants(Employee admin) {
        if (!empty(grantMapper.selectCount(null))) {
            return;
        }
        SysGrant g = new SysGrant();
        g.id = Ids.next("gr");
        g.granteeId = admin.id;
        g.permCode = "handover:export";
        g.scope = "ALL";
        g.startAt = LocalDateTime.now().minusDays(1);
        g.endAt = LocalDateTime.now().plusDays(30);
        g.status = "ACTIVE";
        g.grantedBy = admin.id;
        g.reason = "演示：限时移交导出授权";
        g.createdAt = LocalDateTime.now().minusDays(1);
        grantMapper.insert(g);
    }

    private void seedExportApprovals(Employee admin) {
        if (!empty(exportApprovalMapper.selectCount(null))) {
            return;
        }
        BizExportApproval a = new BizExportApproval();
        a.id = Ids.next("ea");
        a.applicant = admin.name;
        a.purpose = "演示：年度维保台账归档导出（含敏感字段）";
        a.scope = "ALL";
        a.status = "PENDING";
        a.createdAt = LocalDateTime.now().minusHours(6);
        exportApprovalMapper.insert(a);
    }
}