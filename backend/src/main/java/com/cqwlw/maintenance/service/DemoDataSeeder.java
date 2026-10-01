package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Drill;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.Knowledge;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.entity.Rescue;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 演示种子数据（与 mock/data.js 对齐，仅联调用；生产 SEED_DEMO_DATA=false）。
 * 口令只存 BCrypt 哈希，演示密码 123456 与 mock 登录提示一致。
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_SIGNATURE = "https://picsum.photos/seed/em-sig/480/180";

    private final AppProperties props;
    private final CompanyMapper companyMapper;
    private final EmployeeMapper employeeMapper;
    private final UseUnitMapper useUnitMapper;
    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper orderMapper;
    private final MaintainRecordMapper recordMapper;
    private final MessageMapper messageMapper;
    private final RescueMapper rescueMapper;
    private final FaultMapper faultMapper;
    private final DrillMapper drillMapper;
    private final InspectRecordMapper inspectMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ChecklistService checklistService;
    private final WorkOrderService workOrderService;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DemoDataSeeder(AppProperties props, CompanyMapper companyMapper, EmployeeMapper employeeMapper,
                          UseUnitMapper useUnitMapper, ElevatorMapper elevatorMapper,
                          WorkOrderMapper orderMapper, MaintainRecordMapper recordMapper,
                          MessageMapper messageMapper, RescueMapper rescueMapper, FaultMapper faultMapper,
                          DrillMapper drillMapper, InspectRecordMapper inspectMapper,
                          KnowledgeMapper knowledgeMapper, ChecklistService checklistService,
                          WorkOrderService workOrderService) {
        this.props = props;
        this.companyMapper = companyMapper;
        this.employeeMapper = employeeMapper;
        this.useUnitMapper = useUnitMapper;
        this.elevatorMapper = elevatorMapper;
        this.orderMapper = orderMapper;
        this.recordMapper = recordMapper;
        this.messageMapper = messageMapper;
        this.rescueMapper = rescueMapper;
        this.faultMapper = faultMapper;
        this.drillMapper = drillMapper;
        this.inspectMapper = inspectMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.checklistService = checklistService;
        this.workOrderService = workOrderService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isSeedDemoData()) {
            return;
        }
        Long employees = employeeMapper.selectCount(null);
        if (employees != null && employees > 0) {
            return;
        }
        log.warn("SEED_DEMO_DATA=true：正在向【空库】写入演示数据，"
                + "其中包含 4 个口令为 123456 的演示账号（含 SYS_ADMIN，13800000010）。"
                + "生产环境必须置false（AGENTS §2.1）。已存在员工数据时本Seeder 不执行。");
        log.info("开始写入演示种子数据（SEED_DEMO_DATA=true）");
        seed();
        log.info("演示种子数据写入完成");
    }

    private void seed() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        String today = TimeUtil.date(TimeUtil.now());

        // ── 维保单位档案 ──
        Company c = new Company();
        c.id = "co_1";
        c.organizationCode = "91500106MAABU3795M";
        c.name = "重庆博威电梯有限公司";
        c.workMenegerName = "赵敏";
        c.workMenegerPhone = "13723220001";
        companyMapper.insert(c);

        // ── 演示账号 ──
        employee("emp_1", "张伟", "13800000001", "WORKER", "维保人员",
                "6901282369105174537", "CQ3601030001", "2024-03-01", 400, enc);
        employee("emp_2", "陈刚", "13800000002", "LEADER", "班组长",
                "990002", "CQ3601030002", "2023-06-01", 250, enc);
        employee("emp_3", "李强", "13800000004", "WORKER", "维保人员",
                "6901284774286860288", "CQ3601030003", "2024-08-01", 500, enc);
        employee("unit_1", "王芳", "13800000003", "UNIT_ADMIN", "使用单位安全管理员",
                "", "", "", 0, enc);

        // ── 管理端演示账号（账号/初始密码见 README）──
        employee("emp_admin", "郑浩", "13800000009", "ADMIN", "维保部管理员",
                "", "", "", 0, enc);
        employee("emp_sys", "系统管理员", "13800000010", "SYS_ADMIN", "系统管理员",
                "", "", "", 0, enc);

        // ── 使用单位 ──
        useUnit("uu_1", "重庆世纪物业管理有限公司", "刘建国", "13910001001", "王芳", "13800000003", "023-67612345");
        useUnit("uu_2", "重庆蓝湾物业服务有限公司", "周涛", "13930003002", "吴静", "13800003005", "023-67991234");

        // ── 电梯（平台回填字段 + 本地字段 + 维保绑定） ──
        elevator("el_1", "EM-2024-001", "世纪大厦 1# 客梯", "渝北区龙山一路 88 号世纪大厦",
                "TSCQ5001120001", "DT-CQ-2024-001", "KT-01", "OTIS 300VF", "uu_1", 45,
                "SGL20131212-1", "5633318206815862786", "王芳", "13800000003", "023-67612345",
                "106.633520", "29.719210", "奥的斯", "奥的斯电梯（中国）有限公司", "OTIS-2013-8817",
                1000, "1.75", "11/11", "HM", 15, "张伟", "13800000001", "6901282369105174537", "李强", "6901284774286860288", 3);
        elevator("el_2", "EM-2024-002", "世纪大厦 2# 客梯", "渝北区龙山一路 88 号世纪大厦",
                "TSCQ5001120002", "DT-CQ-2024-002", "KT-02", "OTIS 300VF", "uu_1", 18,
                "SGL20131212-2", "5633318206815862786", "王芳", "13800000003", "023-67612345",
                "106.633520", "29.719210", "奥的斯", "奥的斯电梯（中国）有限公司", "OTIS-2013-8818",
                1000, "1.75", "11/11", "HM", 15, "张伟", "13800000001", "6901282369105174537", "李强", "6901284774286860288", 3);
        elevator("el_3", "EM-2024-003", "蓝湾国际 A 座货梯", "江北区滨江路 6 号蓝湾国际",
                "TSCQ5001120003", "DT-CQ-2024-003", "HT-01", "三菱 GPS-III", "uu_2", 200,
                "MITS-2018-0331", "5633318206815862790", "吴静", "13800003005", "023-67991234",
                "106.574210", "29.588660", "三菱", "上海三菱电梯有限公司", "MLS-2018-0331",
                2000, "1.00", "6/6", "FM", 30, "张伟", "13800000001", "6901282369105174537", "", "", 10);
        elevator("el_4", "EM-2024-004", "蓝湾国际 B 座客梯", "江北区滨江路 6 号蓝湾国际",
                "TSCQ5001120004", "DT-CQ-2024-004", "KT-01", "日立 YK", "uu_2", 240,
                "HIT-2021-1102", "5633318206815862790", "吴静", "13800003005", "023-67991234",
                "106.574210", "29.588660", "日立", "日立电梯（中国）有限公司", "HIT-2021-1102",
                1000, "1.50", "8/8", "HM", 15, "李强", "13800000004", "6901284774286860288", "", "", 16);

        // ── 工单（checklist 为空 → 服务层按模板懒加载生成） ──
        order("wo_1", "WO" + today.replace("-", "") + "-001", "el_1", "半月维保", "HM",
                today + " 09:00:00", "PENDING", "张伟", "李强", "6901282369105174537", "6901284774286860288",
                null, null, null, null, null);
        order("wo_2", "WO" + today.replace("-", "") + "-002", "el_3", "救援后复查", "FM",
                today + " 08:30:00", "PROCESSING", "张伟", "", "6901282369105174537", "",
                today + " 08:00:00", null, null, null, null);
        order("wo_3", "WO20260928-011", "el_2", "半月维保", "HM",
                today + " 09:00:00", "DONE", "张伟", "李强", "6901282369105174537", "6901284774286860288",
                today + " 08:52:00", today + " 11:20:00", "02:28:00", "19480012609000011", "REPORTED");
        order("wo_4", "WO20260927-008", "el_3", "困人救援", "FM",
                today + " 16:30:00", "DONE", "张伟", "", "6901282369105174537", "",
                today + " 16:28:00", today + " 18:05:00", "01:37:00", "19480012609000008", "REPORTED");
        order("wo_5", "WO20260926-005", "el_1", "年度维保", "OY",
                today + " 09:00:00", "DONE", "张伟", "李强", "6901282369105174537", "6901284774286860288",
                today + " 09:02:00", today + " 15:40:00", "06:38:00", "19480012609000005", "REPORTED");

        // ── 消息 ──
        message("msg_1", "今日维保任务提醒", "您今日有 2 条维保任务，请按时到场扫码签到", today + " 07:30:00", false);
        message("msg_2", "排班确认通知", "下周排班表已发布，请确认后回复", today + " 06:10:00", false);
        message("msg_3", "平台对接公告", "监管平台业务接口已开放至 2.7，详见联调记录", today + " 08:00:00", true);

        // ── 救援 / 故障 ──
        Rescue r = new Rescue();
        r.id = "rs_1";
        r.elevatorCode = "EM-2024-003";
        r.trappedCount = 3;
        r.descr = "货梯 3 层与 4 层之间停梯困人，已解救";
        r.alarmAt = TimeUtil.parse(today + " 16:10:00");
        r.departAt = TimeUtil.parse(today + " 16:18:00");
        r.arriveAt = TimeUtil.parse(today + " 16:36:00");
        r.rescuedAt = TimeUtil.parse(today + " 16:40:00");
        r.arriveMinutes = 26;
        r.rescuedMinutes = 30;
        r.overtime = false;
        r.reason = "";
        r.action = "盘车平层后开门放人";
        r.status = "已解除";
        r.createdAt = TimeUtil.parse(today + " 16:40:00");
        rescueMapper.insert(r);

        fault("ft_1", "EM-2024-002", "门系统", "1 层厅门关门异响", "OPEN", "", today + " 10:15:00");
        fault("ft_2", "EM-2024-001", "平层异常", "平层偏差明显，已调整", "CLOSED",
                "调整平层感应器后恢复正常", today + " 08:20:00");

        // ── 合规台账 ──
        Drill d = new Drill();
        d.id = "dr_1";
        d.drillDate = LocalDate.now(TimeUtil.ZONE).minusDays(20);
        d.category = "曳引驱动电梯";
        d.scene = "困人救援";
        d.participants = "张伟、李强、王芳";
        d.process = "模拟 3 层困人，按预案盘车平层、开门解救，全程 18 分钟";
        d.problems = "对讲通话音量偏小";
        d.actions = "已调整对讲音量并复测正常";
        d.createdAt = TimeUtil.now();
        drillMapper.insert(d);

        InspectRecord ins = new InspectRecord();
        ins.id = "in_1";
        ins.elevatorId = "el_1";
        ins.inspectDate = LocalDate.now(TimeUtil.ZONE).minusDays(100);
        ins.itemTotal = 76;
        ins.abnormalCount = 1;
        ins.problems = "层门地坎有杂物，已清理";
        ins.inspectorSign = "mock_file_ins1";
        ins.reviewerSign = "mock_file_ins1r";
        inspectMapper.insert(ins);

        // ── 知识库 ──
        knowledge("kb_0", "法定合规动作说明", "应急处置",
                "一、自行检查（TSG 第五条(九)）：每台电梯每年至少 1 次，须在下次定期检验前完成，检查项不少于年度维保项。\n\n"
                        + "二、应急演练（TSG 第五条(三)）：每半年至少 1 轮，覆盖本单位在保的全部电梯品种。\n\n"
                        + "三、困人救援（TSG 第五条(四)）：接报后直辖市 30 分钟内抵达，超时记录不可删除。");
        knowledge("kb_1", "曳引机异响排查手册", "维保技巧",
                "一、听声辨位：机房内分区静听，区分曳引轮、导向轮、电机轴承声源。\n\n"
                        + "二、轴承磨损：连续「沙沙」声且温升偏高，测量振动值超标时更换轴承。\n\n"
                        + "三、曳引轮绳槽磨损：钢丝绳跳动伴随规律性「咯噔」声，检查绳槽磨损量并评估是否重车绳槽。\n\n"
                        + "四、联轴器对中不良：低频「嗡嗡」声随负载变化，复核同轴度偏差应 ≤0.1mm。\n\n"
                        + "五、抱闸间隙：制动器动作时的撞击声，确认闸瓦间隙均匀且 ≤0.7mm。");
        knowledge("kb_2", "门锁回路故障处理流程", "故障处理",
                "一、故障现象：运行中急停或无法启动，控制柜报门锁回路断开。\n\n"
                        + "二、排查顺序：先查厅门锁（逐层定位，注意作业时双人监护并挂牌），再查轿门锁触点。\n\n"
                        + "三、常见原因：门锁触点氧化、门刀与门球间隙异常、皮带打滑导致关门不到位。\n\n"
                        + "四、处理：触点打磨或更换，按本机说明书调整门锁啮合深度 ≥7mm。\n\n"
                        + "五、验证：检修运行全程联动试验，确认门锁回路导通后再恢复运行。");
        knowledge("kb_3", "困人救援标准作业程序", "应急处置",
                "一、接警：3 分钟内响应，确认被困楼层与人数，安抚被困人员切勿扒门。\n\n"
                        + "二、到场：30 分钟红线（TSG T5002 第五条），抵达后先确认轿厢实际位置。\n\n"
                        + "三、盘车：断开主电源、双人配合（一人盘车一人监视），按「就近平层」原则移动轿厢。\n\n"
                        + "四、放人：开门前确认轿厢地坎与层门地坎高差 ≤0.6m，防止踏空坠落。\n\n"
                        + "五、善后：登记救援台账（接警/出动/抵达/解救四时间节点），并安排救援后复查工单。");
        knowledge("kb_4", "V1.5 平台上报字段对照表", "平台对接",
                "一、维保记录（2.4）：originalRecordId 为平台侧记录唯一标识，幂等性未确认前失败不自动重试（REG_RETRY_AUTO=false）。\n\n"
                        + "二、人员同步（2.2）：维保人员需先同步获取 platform_id 才可上报（错误码 1004）。\n\n"
                        + "三、存量记录（2.8）：临时接口，平台关闭后停止推送。\n\n"
                        + "四、签到定位（2.4）：坐标系为 WGS84，位置超阈由后端判定（错误码 1001）。\n\n"
                        + "五、上报重试：平台侧自动重试保持关闭，失败转人工处理。");

        // ── 使用单位待确认记录（含报文快照冻结） ──
        unitRecord("ur_1", "世纪大厦 1# 客梯", "EM-2024-001", "半月维保", "HM",
                "张伟", "李强", "6901282369105174537", "6901284774286860288",
                today + " 08:52:00", today + " 11:20:00", "02:28:00",
                "HM", true, "19480012609000011", "REPORTED", 15, "PENDING", null, "", "");
        unitRecord("ur_2", "蓝湾国际 A 座货梯", "EM-2024-003", "困人救援", "FM",
                "张伟", "", "6901282369105174537", "",
                today + " 16:28:00", today + " 18:05:00", "01:37:00",
                "FM", false, "19480012609000008", "REPORTED", 30, "CONFIRMED", 5,
                "mock_file_sig1", DEMO_SIGNATURE);
    }

    private Employee employee(String id, String name, String phone, String role, String roleText,
                              String platformId, String certificate, String workStartDate,
                              int workEndInDays, BCryptPasswordEncoder enc) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        e.phone = phone;
        e.account = phone;
        e.passwordHash = enc.encode("123456");
        e.role = role;
        e.roleText = roleText;
        e.openid = null;
        e.platformId = platformId;
        e.certificate = certificate;
        e.workStartDate = workStartDate;
        e.workEndDate = TimeUtil.date(TimeUtil.now().plusDays(workEndInDays));
        e.workStat = role.equals("UNIT_ADMIN") ? "" : "normal";
        e.syncStatus = role.equals("UNIT_ADMIN") ? "NOT_SYNCED" : "ACTIVE";
        employeeMapper.insert(e);
        return e;
    }

    private void useUnit(String id, String unitName, String principal, String principalPhone,
                         String administer, String administerPhone, String emergencyPhone) {
        UseUnit u = new UseUnit();
        u.id = id;
        u.unitName = unitName;
        u.unitPrincipal = principal;
        u.unitPrincipalPhone = principalPhone;
        u.elevatorAdminister = administer;
        u.elevatorAdministerPhone = administerPhone;
        u.emergencyPhone = emergencyPhone;
        useUnitMapper.insert(u);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void elevator(String id, String code, String name, String location,
                          String regCode, String deviceCode, String insideNumber, String model,
                          String useUnitId, int nextCheckInDays, String factoryNumber, String useUnitEntityId,
                          String administer, String administerPhone, String emergencyPhone,
                          String lng, String lat, String brand, String manufacturer, String productNo,
                          int ratedLoad, String ratedSpeed, String stationsDoors,
                          String workTypeCode, int intervalDays,
                          String workerName, String workerPhone, String workerPlatformId,
                          String assistantName, String assistantPlatformId, int lastMaintDaysAgo) {
        Elevator el = new Elevator();
        el.id = id;
        el.elevatorCode = code;
        el.elevatorName = name;
        el.location = location;
        el.regCode = regCode;
        el.deviceCode = deviceCode;
        el.insideNumber = insideNumber;
        el.model = model;
        el.useUnitId = useUnitId;
        el.category = "曳引驱动电梯";
        el.nextCheckDate = LocalDate.now(TimeUtil.ZONE).plusDays(nextCheckInDays);
        el.factoryNumber = factoryNumber;
        el.useUnitEntityId = useUnitEntityId;
        el.elevatorAdminister = administer;
        el.elevatorAdministerPhone = administerPhone;
        el.emergencyPhone = emergencyPhone;
        el.platformSyncedAt = TimeUtil.parse("2026-09-29 14:20:00");
        el.lng = new BigDecimal(lng);
        el.lat = new BigDecimal(lat);
        el.brand = brand;
        el.manufacturer = manufacturer;
        el.productNo = productNo;
        el.driveMode = "曳引驱动";
        el.ratedLoad = ratedLoad;
        el.ratedLoadUnit = "kg";
        el.ratedSpeed = new BigDecimal(ratedSpeed);
        el.ratedSpeedUnit = "m/s";
        el.stationsDoors = stationsDoors;
        el.workTypeCode = workTypeCode;
        el.intervalDays = intervalDays;
        el.workerName = workerName;
        el.workerPhone = workerPhone;
        el.workerPlatformId = workerPlatformId;
        el.assistantName = assistantName;
        el.assistantPlatformId = assistantPlatformId;
        el.lastMaintenanceAt = TimeUtil.now().minusDays(lastMaintDaysAgo);
        elevatorMapper.insert(el);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void order(String id, String orderNo, String elevatorId, String workType, String workTypeCode,
                       String planTime, String status, String workerName, String assistantName,
                       String workerPlatformId, String assistantPlatformId,
                       String checkinTime, String checkoutTime, String duration,
                       String originalRecordId, String reportStatus) {
        WorkOrder o = new WorkOrder();
        o.id = id;
        o.orderNo = orderNo;
        o.elevatorId = elevatorId;
        o.workType = workType;
        o.workTypeCode = workTypeCode;
        o.planTime = TimeUtil.parse(planTime);
        o.status = status;
        o.workerName = workerName;
        o.assistantName = assistantName;
        o.workerPlatformId = workerPlatformId;
        o.assistantPlatformId = assistantPlatformId;
        o.checkinTime = checkinTime == null ? null : TimeUtil.parse(checkinTime);
        o.checkoutTime = checkoutTime == null ? null : TimeUtil.parse(checkoutTime);
        o.duration = duration;
        o.originalRecordId = originalRecordId;
        o.reportStatus = reportStatus;
        o.autoDispatched = false;
        orderMapper.insert(o);
    }

    private void message(String id, String title, String content, String createdAt, boolean read) {
        Message m = new Message();
        m.id = id;
        m.title = title;
        m.content = content;
        m.createdAt = TimeUtil.parse(createdAt);
        m.readFlag = read;
        messageMapper.insert(m);
    }

    private void fault(String id, String elevatorCode, String faultType, String descr,
                       String status, String handleDesc, String createdAt) {
        Fault f = new Fault();
        f.id = id;
        f.elevatorCode = elevatorCode;
        f.faultType = faultType;
        f.descr = descr;
        f.status = status;
        f.handleDesc = handleDesc;
        f.createdAt = TimeUtil.parse(createdAt);
        faultMapper.insert(f);
    }

    private void knowledge(String id, String title, String tag, String content) {
        Knowledge k = new Knowledge();
        k.id = id;
        k.title = title;
        k.tag = tag;
        k.content = content;
        knowledgeMapper.insert(k);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void unitRecord(String id, String elevatorName, String elevatorCode, String workType,
                            String workTypeCode, String workerName, String assistantName,
                            String workerPlatformId, String assistantPlatformId,
                            String checkinTime, String checkoutTime, String duration,
                            String doneType, boolean withAbnormal, String originalRecordId,
                            String reportStatus, int nextMaintInDays, String confirmStatus,
                            Integer satisfaction, String signatureFileId, String signatureUrl) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorName = elevatorName;
        r.elevatorCode = elevatorCode;
        r.workType = workType;
        r.workTypeCode = workTypeCode;
        r.workerName = workerName;
        r.assistantName = assistantName;
        r.workerPlatformId = workerPlatformId;
        r.assistantPlatformId = assistantPlatformId;
        r.checkinTime = TimeUtil.parse(checkinTime);
        r.checkoutTime = TimeUtil.parse(checkoutTime);
        r.duration = duration;
        r.itemsJson = JsonUtil.write(checklistService.makeDoneItems(doneType, withAbnormal, "曳引驱动电梯"));
        r.photosJson = JsonUtil.write(withAbnormal
                ? List.of("https://picsum.photos/seed/em-elev/600/450") : List.of());
        r.workerSignatureUrl = DEMO_SIGNATURE;
        r.assistantSignatureUrl = "";
        r.problemCodesJson = JsonUtil.write(withAbnormal ? List.of("S5") : List.of("S0"));
        r.originalRecordId = originalRecordId;
        r.reportStatus = reportStatus;
        r.retryCount = 0;
        r.nextMaintenanceDate = LocalDate.now(TimeUtil.ZONE).plusDays(nextMaintInDays);
        r.confirmStatus = confirmStatus;
        r.satisfaction = satisfaction;
        r.signatureFileId = signatureFileId;
        r.signatureUrl = signatureUrl;
        r.shareToken = "sg" + Long.toString(System.currentTimeMillis(), 36) + id;
        r.createdAt = TimeUtil.parse(checkoutTime);
        Elevator el = elevatorMapper.selectOne(
                new LambdaQueryWrapper<Elevator>()
                        .eq(Elevator::getElevatorCode, elevatorCode).last("LIMIT 1"));
        UseUnit uu = el == null ? null : useUnitMapper.selectById(el.useUnitId);
        r.reportPayloadJson = JsonUtil.write(workOrderService.buildReportPayload(r, el, uu));
        recordMapper.insert(r);
    }
}
