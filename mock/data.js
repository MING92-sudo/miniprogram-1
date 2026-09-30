// Mock 数据仓库（仅 config.useMock=true 时生效；后端就绪后可整体删除 mock/ 目录）
// 数据为内存态：小程序冷启动后重置，一次会话内保持状态流转
const { formatTime, formatDuration, parseTime } = require('../utils/util')
const {
  APPENDIX_TPLS,
  FREQ_CHAIN,
  FREQ_LABELS,
  CATEGORY_APPENDIX
} = require('./checklist-template')

// 演示用网络图片（正式环境为 COS 文件 url）
const DEMO_PHOTO = 'https://picsum.photos/seed/em-elev/600/450'
const DEMO_SIGNATURE = 'https://picsum.photos/seed/em-sig/480/180'

// 按维保频次生成 TSG 附件A 累加式检查清单（docs/01 §3.7.3）：
// HM=表A-1(31项)；TM=+表A-2(44)；SM=+表A-3(59)；OY=+表A-4(76)；FM 按需取半月基础表
// 周期性/季节性条目（execCycleMonth/ageCondition/seasonWindow）默认"本次无需执行"，
// 灰显不计入未完成项，允许人工点选"本次仍要执行"（docs/03 §6.1）
function buildChecklist(workTypeCode, categoryCode) {
  const chain = FREQ_CHAIN[workTypeCode] || FREQ_CHAIN.HM
  const appendix = CATEGORY_APPENDIX[categoryCode] || 'A'
  const tplMap = require('./checklist-template').APPENDIX_TPLS[appendix]
  const items = []
  chain.forEach(function (freq) {
    tplMap[freq].forEach(function (tpl) {
      const periodic = !!(tpl.execCycleMonth || tpl.ageCondition || tpl.seasonWindow)
      items.push({
        id: 'ci_' + tpl.itemCode,
        itemCode: tpl.itemCode, // 附件-频次-序号（如 A-1-29）
        seq: tpl.seq,
        freq: tpl.freq,
        freqLabel: FREQ_LABELS[appendix][freq],
        name: tpl.name,
        requirement: tpl.requirement, // TSG 原文"维护保养基本要求"
        judgeType: tpl.judgeType,
        valueMin: tpl.valueMin,
        valueMax: tpl.valueMax,
        valueUnit: tpl.valueUnit,
        isKey: tpl.isKey, // TSG 注A-2：要求含试验/测试/校验/检测
        photoRequired: tpl.photoRequired,
        ageCondition: tpl.ageCondition,
        execCycleMonth: tpl.execCycleMonth,
        seasonWindow: tpl.seasonWindow,
        notInThisRun: periodic, // 周期条目演示默认本次无需执行
        nextRunText: periodic ? '下次执行：2027-03' : '',
        result: null, // NORMAL | ABNORMAL | NA（docs/04 A.2 枚举）
        value: null, // NUMERIC 读数
        valueText: '', // MANUFACTURER 说明书判定文字结论
        abnormalDesc: '', // ABNORMAL 异常描述（必填）
        problemCode: '', // ABNORMAL 隐患码 S1-S7（平台 2.6 problemCode 来源）
        skipReason: '', // NA 跳过原因（必填，TSG 注 A-1）
        photos: [],
        photoFileIds: [],
        recordedAt: ''
      })
    })
  })
  return items
}

// 预填"已完成"演示工单的检查项（关键项附照片留证，周期条目保持"本次无需执行"）
function makeDoneItems(workTypeCode, withAbnormal, categoryCode) {
  const items = buildChecklist(workTypeCode, categoryCode)
  items.forEach(function (it, idx) {
    if (it.notInThisRun) return // 周期条目灰显不填
    if (it.judgeType === 'NUMERIC') {
      it.result = 'NORMAL'
      it.value = it.valueMin != null ? it.valueMin + 1 : 8 // 取"达标值+1"演示
    } else if (it.judgeType === 'MANUFACTURER') {
      it.result = 'NORMAL'
      it.valueText = '符合本机说明书要求'
    } else {
      it.result = 'NORMAL'
    }
    if (it.isKey && it.photoRequired) {
      // 关键项（试验/测试/校验/检测类）强制照片留证（TSG 注A-2）
      it.photos = [DEMO_PHOTO + '?k=' + it.itemCode]
      it.photoFileIds = ['mock_file_' + it.itemCode]
    }
    it.recordedAt = formatTime()
  })
  if (withAbnormal) {
    const item = items.find(function (i) {
      return !i.notInThisRun && (i.itemCode === 'A-1-18' || i.name.indexOf('报警') > -1)
    }) || items.find(function (i) { return !i.notInThisRun })
    item.result = 'ABNORMAL'
    item.abnormalDesc = '轿内报警装置通话杂音大，已清洁触点并复测'
    item.problemCode = 'S5'
    item.photos = [DEMO_PHOTO + '?abn=' + item.itemCode]
    item.photoFileIds = ['mock_file_abn_' + item.itemCode]
  }
  return items
}

function today(t) {
  return formatTime().slice(0, 11) + t
}

// 下次维保日期（按维保类别间隔天数推导，01 §3.6.1）
const WORK_TYPE_INTERVAL_DAYS = { FM: 30, HM: 15, TM: 90, SM: 180, OY: 365 }

function addDays(n) {
  return formatTime(new Date(Date.now() + n * 86400000)).slice(0, 10)
}

const db = {
  // 维保单位档案（平台 2.6 冻结字段来源：workMenegerName/workMenegerPhone，拼写与规范 2.6 原文一致）
  company: {
    organizationCode: '91500106MAABU3795M',
    name: '重庆博威电梯有限公司',
    workMenegerName: '赵敏',
    workMenegerPhone: '13723220001'
  },

  // 使用单位档案（平台 2.6 冻结字段来源：unitPrincipal/elevatorAdminister/emergencyPhone）
  useUnits: [
    { id: 'uu_1', unitName: '重庆世纪物业管理有限公司', unitPrincipal: '刘建国', unitPrincipalPhone: '13910001001', elevatorAdminister: '王芳', elevatorAdministerPhone: '13800000003', emergencyPhone: '023-67612345' },
    { id: 'uu_2', unitName: '重庆蓝湾物业服务有限公司', unitPrincipal: '周涛', unitPrincipalPhone: '13930003002', elevatorAdminister: '吴静', elevatorAdministerPhone: '13800003005', emergencyPhone: '023-67991234' }
  ],

  // 演示账号（正式版由 /auth/wx-login + 绑定流程产生）；platformId 为平台人员ID（2.5 同步产物）
  // 2.5 规范字段对齐：workManCertificate=同步匹配键（严禁姓名匹配）、workStartDate/workEndDate=
  // 合同期（到期从平台列表消失→不可派工）、workStat=normal 才可派工、syncStatus=本地同步状态
  // 登录方式（用户需求）：账号由维保单位系统分配，手机号为账号（account），演示密码统一 123456
  employees: {
    WORKER: { id: 'emp_1', name: '张伟', phone: '13800000001', account: '13800000001', password: '123456', role: 'WORKER', roleText: '维保人员', platformId: '6901282369105174537',
      certificate: 'CQ3601030001', workStartDate: '2024-03-01', workEndDate: addDays(400), workStat: 'normal', syncStatus: 'ACTIVE' },
    LEADER: { id: 'emp_2', name: '陈刚', phone: '13800000002', account: '13800000002', password: '123456', role: 'LEADER', roleText: '班组长', platformId: '990002',
      certificate: 'CQ3601030002', workStartDate: '2023-06-01', workEndDate: addDays(250), workStat: 'normal', syncStatus: 'ACTIVE' },
    WORKER2: { id: 'emp_3', name: '李强', phone: '13800000004', account: '13800000004', password: '123456', role: 'WORKER', roleText: '维保人员', platformId: '6901284774286860288',
      certificate: 'CQ3601030003', workStartDate: '2024-08-01', workEndDate: addDays(500), workStat: 'normal', syncStatus: 'ACTIVE' },
    UNIT_ADMIN: { id: 'unit_1', name: '王芳', phone: '13800000003', account: '13800000003', password: '123456', role: 'UNIT_ADMIN', roleText: '使用单位安全管理员', platformId: '',
      certificate: '', workStartDate: '', workEndDate: '', workStat: '', syncStatus: 'NOT_SYNCED' }
  },

  elevators: [
    { id: 'el_1', elevatorCode: 'EM-2024-001', elevatorName: '世纪大厦 1# 客梯', location: '渝北区龙山一路 88 号世纪大厦', regCode: 'TSCQ5001120001', deviceCode: 'DT-CQ-2024-001', insideNumber: 'KT-01', model: 'OTIS 300VF', useUnitId: 'uu_1', category: '曳引驱动电梯', nextCheckDate: addDays(45),
      // ── 平台 2.7 自动获取字段（8 个，电话未脱敏，可直接回填用于 2.6 上报）──
      factoryNumber: 'SGL20131212-1', useUnitEntityId: '5633318206815862786',
      elevatorAdminister: '王芳', elevatorAdministerPhone: '13800000003', emergencyPhone: '023-67612345',
      platformSyncedAt: '2026-09-29 14:20:00',
      // ── 本地维护字段（平台不提供：地址/经纬度/型号品牌/制造单位/下次检验）──
      lng: 106.633520, lat: 29.719210,
      brand: '奥的斯', manufacturer: '奥的斯电梯（中国）有限公司', productNo: 'OTIS-2013-8817',
      driveMode: '曳引驱动', ratedLoad: 1000, ratedLoadUnit: 'kg', ratedSpeed: 1.75, ratedSpeedUnit: 'm/s', stationsDoors: '11/11',
      // 维保绑定配置：到期自动派单的人员与频次（正式版为维保合同 + 排班表）
      maintenance: { workTypeCode: 'HM', intervalDays: 15, workerName: '张伟', workerPhone: '13800000001', workerPlatformId: '990001', assistantName: '李强', assistantPlatformId: '990003', lastMaintenanceAt: addDays(-3) } },
    { id: 'el_2', elevatorCode: 'EM-2024-002', elevatorName: '世纪大厦 2# 客梯', location: '渝北区龙山一路 88 号世纪大厦', regCode: 'TSCQ5001120002', deviceCode: 'DT-CQ-2024-002', insideNumber: 'KT-02', model: 'OTIS 300VF', useUnitId: 'uu_1', category: '曳引驱动电梯', nextCheckDate: addDays(18),
      factoryNumber: 'SGL20131212-2', useUnitEntityId: '5633318206815862786',
      elevatorAdminister: '王芳', elevatorAdministerPhone: '13800000003', emergencyPhone: '023-67612345',
      platformSyncedAt: '2026-09-29 14:20:00',
      lng: 106.633520, lat: 29.719210,
      brand: '奥的斯', manufacturer: '奥的斯电梯（中国）有限公司', productNo: 'OTIS-2013-8818',
      driveMode: '曳引驱动', ratedLoad: 1000, ratedLoadUnit: 'kg', ratedSpeed: 1.75, ratedSpeedUnit: 'm/s', stationsDoors: '11/11',
      maintenance: { workTypeCode: 'HM', intervalDays: 15, workerName: '张伟', workerPlatformId: '990001', assistantName: '李强', assistantPlatformId: '990003', lastMaintenanceAt: addDays(-3) } },
    { id: 'el_3', elevatorCode: 'EM-2024-003', elevatorName: '蓝湾国际 A 座货梯', location: '江北区滨江路 6 号蓝湾国际', regCode: 'TSCQ5001120003', deviceCode: 'DT-CQ-2024-003', insideNumber: 'HT-01', model: '三菱 GPS-III', useUnitId: 'uu_2', category: '曳引驱动电梯', nextCheckDate: addDays(200),
      factoryNumber: 'MITS-2018-0331', useUnitEntityId: '5633318206815862790',
      elevatorAdminister: '吴静', elevatorAdministerPhone: '13800003005', emergencyPhone: '023-67991234',
      platformSyncedAt: '2026-09-29 14:25:00',
      lng: 106.574210, lat: 29.588660,
      brand: '三菱', manufacturer: '上海三菱电梯有限公司', productNo: 'MLS-2018-0331',
      driveMode: '曳引驱动', ratedLoad: 2000, ratedLoadUnit: 'kg', ratedSpeed: 1.0, ratedSpeedUnit: 'm/s', stationsDoors: '6/6',
      maintenance: { workTypeCode: 'FM', intervalDays: 30, workerName: '张伟', workerPhone: '13800000001', workerPlatformId: '990001', assistantName: '', assistantPlatformId: '', lastMaintenanceAt: addDays(-10) } },
    { id: 'el_4', elevatorCode: 'EM-2024-004', elevatorName: '蓝湾国际 B 座客梯', location: '江北区滨江路 6 号蓝湾国际', regCode: 'TSCQ5001120004', deviceCode: 'DT-CQ-2024-004', insideNumber: 'KT-01', model: '日立 YK', useUnitId: 'uu_2', category: '曳引驱动电梯', nextCheckDate: addDays(240),
      factoryNumber: 'HIT-2021-1102', useUnitEntityId: '5633318206815862790',
      elevatorAdminister: '吴静', elevatorAdministerPhone: '13800003005', emergencyPhone: '023-67991234',
      platformSyncedAt: '2026-09-29 14:25:00',
      lng: 106.574210, lat: 29.588660,
      brand: '日立', manufacturer: '日立电梯（中国）有限公司', productNo: 'HIT-2021-1102',
      driveMode: '曳引驱动', ratedLoad: 1000, ratedLoadUnit: 'kg', ratedSpeed: 1.5, ratedSpeedUnit: 'm/s', stationsDoors: '8/8',
      // 演示到期自动派单：上次维保 16 天前，已超半月周期 → 进入工单台即自动生成并派给李强
      maintenance: { workTypeCode: 'HM', intervalDays: 15, workerName: '李强', workerPhone: '13800000004', workerPlatformId: '990003', assistantName: '', assistantPlatformId: '', lastMaintenanceAt: addDays(-16) } }
  ],

  orders: [
    {
      id: 'wo_1', orderNo: 'WO20260929-001', elevatorId: 'el_1',
      workType: '半月维保', workTypeCode: 'HM', planTime: today('09:00:00'), status: 'PENDING',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: '', checkoutTime: '', duration: '', originalRecordId: '', reportStatus: '',
      checklist: buildChecklist('HM', '曳引驱动电梯')
    },
    {
      id: 'wo_2', orderNo: 'WO20260929-002', elevatorId: 'el_3',
      workType: '救援后复查', workTypeCode: 'FM', planTime: today('08:30:00'), status: 'PROCESSING',
      workerName: '张伟', assistantName: '', workerPlatformId: '990001', assistantPlatformId: '',
      // 签到时间留足 30 分钟作业时长下限（constants MIN_WORK_DURATION_MINUTES），保证演示可直接签退
      checkinTime: today('08:00:00'), checkoutTime: '', duration: '', originalRecordId: '', reportStatus: '',
      checklist: buildChecklist('FM', '曳引驱动电梯')
    },
    {
      id: 'wo_3', orderNo: 'WO20260928-011', elevatorId: 'el_2',
      workType: '半月维保', workTypeCode: 'HM', planTime: today('09:00:00'), status: 'DONE',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('08:52:00'), checkoutTime: today('11:20:00'), duration: '02:28:00',
      originalRecordId: '19480012609000011', reportStatus: 'REPORTED',
      checklist: makeDoneItems('HM', true, '曳引驱动电梯')
    },
    {
      id: 'wo_4', orderNo: 'WO20260927-008', elevatorId: 'el_3',
      workType: '困人救援', workTypeCode: 'FM', planTime: today('16:30:00'), status: 'DONE',
      workerName: '张伟', assistantName: '', workerPlatformId: '990001', assistantPlatformId: '',
      checkinTime: today('16:28:00'), checkoutTime: today('18:05:00'), duration: '01:37:00',
      originalRecordId: '19480012609000008', reportStatus: 'REPORTED',
      checklist: makeDoneItems('FM', false, '曳引驱动电梯')
    },
    {
      id: 'wo_5', orderNo: 'WO20260926-005', elevatorId: 'el_1',
      workType: '年度维保', workTypeCode: 'OY', planTime: today('09:00:00'), status: 'DONE',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('09:02:00'), checkoutTime: today('15:40:00'), duration: '06:38:00',
      originalRecordId: '19480012609000005', reportStatus: 'REPORTED',
      checklist: makeDoneItems('OY', false, '曳引驱动电梯')
    }
  ],

  messages: [
    { id: 'msg_1', title: '今日维保任务提醒', content: '您今日有 2 条维保任务，请按时到场扫码签到', createdAt: today('07:30:00'), read: false },
    { id: 'msg_2', title: '排班确认通知', content: '下周排班表已发布，请确认后回复', createdAt: today('06:10:00'), read: false },
    { id: 'msg_3', title: '平台对接公告', content: '监管平台业务接口已开放至 2.7，详见联调记录', createdAt: today('08:00:00'), read: true }
  ],

  rescues: [
    { id: 'rs_1', elevatorCode: 'EM-2024-003', trappedCount: 3, desc: '货梯 3 层与 4 层之间停梯困人，已解救', status: '已解除', createdAt: today('16:40:00') }
  ],

  faults: [
    { id: 'ft_1', elevatorCode: 'EM-2024-002', faultType: '门系统', desc: '1 层厅门关门异响', status: 'OPEN', createdAt: today('10:15:00'), handleDesc: '' },
    { id: 'ft_2', elevatorCode: 'EM-2024-001', faultType: '平层异常', desc: '平层偏差明显，已调整', status: 'CLOSED', createdAt: today('08:20:00'), handleDesc: '调整平层感应器后恢复正常' }
  ],

  // ── 合规台账（TSG 法定项，docs/01 §3.17/3.18；数据暂存本地，不上报平台）──
  // 应急演练：每半年至少 1 轮，覆盖本单位在保的全部电梯品种
  drills: [
    {
      id: 'dr_1', drillDate: addDays(-20), category: '曳引驱动电梯', scene: '困人救援',
      participants: '张伟、李强、王芳',
      process: '模拟 3 层困人，按预案盘车平层、开门解救，全程 18 分钟',
      problems: '对讲通话音量偏小',
      actions: '已调整对讲音量并复测正常'
    }
  ],

  // 自行检查：每台电梯每年至少 1 次，须在下次定期检验前完成（复用年度维保检查项）
  inspects: [
    {
      id: 'in_1', elevatorId: 'el_1', inspectDate: addDays(-100),
      itemTotal: 76, abnormalCount: 1,
      problems: '层门地坎有杂物，已清理',
      inspectorSign: 'mock_file_ins1', reviewerSign: 'mock_file_ins1r'
    }
  ],

  // 使用单位待确认记录（签退时冻结的维保记录快照）
  unitRecords: [
    {
      id: 'ur_1', elevatorName: '世纪大厦 1# 客梯', elevatorCode: 'EM-2024-001',
      workType: '半月维保', workTypeCode: 'HM',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('08:52:00'), checkoutTime: today('11:20:00'), duration: '02:28:00',
      items: makeDoneItems('HM', true, '曳引驱动电梯'), photos: [DEMO_PHOTO],
      workerSignatureUrl: DEMO_SIGNATURE,
      problemCodes: ['S5'],
      originalRecordId: '19480012609000011', reportStatus: 'REPORTED', retryCount: 0,
      nextMaintenanceDate: addDays(15),
      confirmStatus: 'PENDING', satisfaction: null, signatureFileId: '', signatureUrl: ''
    },
    {
      id: 'ur_2', elevatorName: '蓝湾国际 A 座货梯', elevatorCode: 'EM-2024-003',
      workType: '困人救援', workTypeCode: 'FM',
      workerName: '张伟', assistantName: '', workerPlatformId: '990001', assistantPlatformId: '',
      checkinTime: today('16:28:00'), checkoutTime: today('18:05:00'), duration: '01:37:00',
      items: makeDoneItems('FM', false, '曳引驱动电梯'), photos: [],
      workerSignatureUrl: DEMO_SIGNATURE,
      problemCodes: ['S0'], // 无隐患必须填 S0（平台 2.6 约定）
      originalRecordId: '19480012609000008', reportStatus: 'REPORTED', retryCount: 0,
      nextMaintenanceDate: addDays(30),
      confirmStatus: 'CONFIRMED', satisfaction: 5, signatureFileId: 'mock_file_sig1', signatureUrl: DEMO_SIGNATURE
    }
  ],

  knowledge: [
    {
      id: 'kb_0',
      title: '法定合规动作说明',
      tag: '应急处置',
      content: '一、自行检查（TSG 第五条(九)）：每台电梯每年至少 1 次，须在下次定期检验前完成，检查项不少于年度维保项。\n\n二、应急演练（TSG 第五条(三)）：每半年至少 1 轮，覆盖本单位在保的全部电梯品种。\n\n三、困人救援（TSG 第五条(四)）：接报后直辖市 30 分钟内抵达，超时记录不可删除。'
    },
    {
      id: 'kb_1',
      title: '曳引机异响排查手册',
      tag: '维保技巧',
      content: '一、听声辨位：机房内分区静听，区分曳引轮、导向轮、电机轴承声源。\n\n二、轴承磨损：连续"沙沙"声且温升偏高，测量振动值超标时更换轴承。\n\n三、曳引轮绳槽磨损：钢丝绳跳动伴随规律性"咯噔"声，检查绳槽磨损量并评估是否重车绳槽。\n\n四、联轴器对中不良：低频"嗡嗡"声随负载变化，复核同轴度偏差应 ≤0.1mm。\n\n五、抱闸间隙：制动器动作时的撞击声，确认闸瓦间隙均匀且 ≤0.7mm。'
    },
    {
      id: 'kb_2',
      title: '门锁回路故障处理流程',
      tag: '故障处理',
      content: '一、故障现象：运行中急停或无法启动，控制柜报门锁回路断开。\n\n二、排查顺序：先查厅门锁（逐层定位，注意作业时双人监护并挂牌），再查轿门锁触点。\n\n三、常见原因：门锁触点氧化、门刀与门球间隙异常、皮带打滑导致关门不到位。\n\n四、处理：触点打磨或更换，按本机说明书调整门锁啮合深度 ≥7mm。\n\n五、验证：检修运行全程联动试验，确认门锁回路导通后再恢复运行。'
    },
    {
      id: 'kb_3',
      title: '困人救援标准作业程序',
      tag: '应急处置',
      content: '一、接警：3 分钟内响应，确认被困楼层与人数，安抚被困人员切勿扒门。\n\n二、到场：30 分钟红线（TSG T5002 第五条），抵达后先确认轿厢实际位置。\n\n三、盘车：断开主电源、双人配合（一人盘车一人监视），按"就近平层"原则移动轿厢。\n\n四、放人：开门前确认轿厢地坎与层门地坎高差 ≤0.6m，防止踏空坠落。\n\n五、善后：登记救援台账（接警/出动/抵达/解救四时间节点），并安排救援后复查工单。'
    },
    {
      id: 'kb_4',
      title: 'V1.5 平台上报字段对照表',
      tag: '平台对接',
      content: '一、维保记录（2.4）：originalRecordId 为平台侧记录唯一标识，幂等性未确认前失败不自动重试（REG_RETRY_AUTO=false）。\n\n二、人员同步（2.2）：维保人员需先同步获取 platform_id 才可上报（错误码 1004）。\n\n三、存量记录（2.8）：临时接口，平台关闭后停止推送。\n\n四、签到定位（2.4）：坐标系为 WGS84，位置超阈由后端判定（错误码 1001）。\n\n五、上报重试：平台侧自动重试保持关闭，失败转人工处理。'
    }
  ]
}

// ── 数据操作 ────────────────────────────────────────────────

function nextId(prefix) {
  return prefix + '_' + Date.now()
}

// 雪花ID样式演示（正式版由后端雪花算法生成）
function nextRecordId() {
  return '1948' + String(Date.now()) + '01'
}

// ── 自动排期引擎（mock 演示：对齐 docs/04 设计的 /plans/generate + /plans/assign）─────
// 规则：
//   1) 电梯维护配置（maintenance）绑定：维保频次周期 + 主维保/配合人员（含平台ID）；
//   2) 上次签退时间（无历史时取 lastMaintenanceAt）+ 周期天数 = 下次维保到期日；
//   3) **到期前一天**自动把名下全部到期电梯派给对应维保人员（一次性全部派单，不限时段/台数——
//      作业时长由签退时 30 分钟校验把关，即"只验证作业时间"，用户确认 2026-09-30）；
//   4) 保养类型按时间自动升级（TSG 累加式：年365/半年180/季90/半月15）；
//   5) 派单同时写入消息中心通知。
// 真实后端实现为：plans 计划表 + 定时任务（到期前一天触发）+ 派单通知。
const WORK_TYPE_LABEL = { HM: '半月维保', TM: '季度维保', SM: '半年维保', OY: '年度维保', FM: '按需维保' }

function ensureDueOrders() {
  const now = Date.now()
  const created = []
  db.elevators.forEach(function (el) {
    const cfg = el.maintenance
    if (!cfg) return
    // 该电梯已有进行中/待办工单（任意类型）→ 一次只派一单
    const hasActive = db.orders.some(function (o) {
      return o.elevatorId === el.id && o.status !== 'DONE'
    })
    if (hasActive) return
    // 上次维保时间（任意类型）：最近一次 DONE 工单的签退时间
    let last = 0
    db.orders.forEach(function (o) {
      if (o.elevatorId === el.id && o.status === 'DONE' && o.checkoutTime) {
        const t = parseTime(o.checkoutTime)
        if (t > last) last = t
      }
    })
    if (!last) last = parseTime(cfg.lastMaintenanceAt)
    if (!last) return
    // 到期日 = 上次维保 + 周期天数；**到期前一天**即自动派单（用户规则）
    const intervalMs = (cfg.intervalDays || WORK_TYPE_INTERVAL_DAYS[cfg.workTypeCode] || 15) * 86400000
    const dueMs = last + intervalMs
    if (now < dueMs - 86400000) return // 未到"到期前一天"，暂不派单
    // ★ 保养类型按时间自动升级（TSG 附件A 累加式：季度=半月+季度项，半年=+半年项，年度=+年度项）：
    //   距上次年度维保 ≥365 天 → 本次派年度单（76 项清单）
    //   距上次半年维保 ≥180 天 → 半年单（59 项）；距上次季度维保 ≥90 天 → 季度单（44 项）；否则半月单（31 项）
    //   某类型从未执行时以其基线日期（lastMaintenanceAt/上次任意维保）起算
    const lastOf = function (c) {
      let t = 0
      db.orders.forEach(function (o) {
        if (o.elevatorId === el.id && o.workTypeCode === c && o.status === 'DONE' && o.checkoutTime) {
          const tt = parseTime(o.checkoutTime)
          if (tt > t) t = tt
        }
      })
      return t || last
    }
    const dueType = function (c, days) {
      return now - lastOf(c) >= days * 86400000
    }
    let code = 'HM'
    if (dueType('OY', 365)) code = 'OY'
    else if (dueType('SM', 180)) code = 'SM'
    else if (dueType('TM', 90)) code = 'TM'
    // 计划时间 = 到期日当天 09:00（已过期则记为今日，前端统计呈"保养超期"）
    const dueDay = formatTime(new Date(dueMs)).slice(0, 10)
    const todayStr = formatTime().slice(0, 10)
    const planDay = dueDay >= todayStr ? dueDay : todayStr
    const planTime = planDay + ' 09:00:00'
    const seq = String(db.orders.length + 1).padStart(3, '0')
    const order = {
      id: nextId('wo'),
      orderNo: 'WO' + formatTime().slice(0, 10).replace(/-/g, '') + '-' + seq,
      elevatorId: el.id,
      workType: WORK_TYPE_LABEL[code] || code,
      workTypeCode: code,
      planTime: planTime,
      status: 'PENDING',
      workerName: cfg.workerName,
      assistantName: cfg.assistantName || '',
      workerPlatformId: cfg.workerPlatformId || '',
      assistantPlatformId: cfg.assistantPlatformId || '',
      checkinTime: '', checkoutTime: '', duration: '', originalRecordId: '', reportStatus: '',
      autoDispatched: true, // 自动派单标识（管理端/后端可追溯）
      checklist: buildChecklist(code, el.category)
    }
    db.orders.unshift(order)
    db.messages.unshift({
      id: nextId('msg'),
      title: '自动派单通知',
      content: el.elevatorName + ' ' + order.workType + '已到维保周期（上次维保 ' +
        formatTime(last).slice(0, 10) + '），按绑定关系自动派给 ' + cfg.workerName + '，请及时扫码签到。',
      createdAt: formatTime(),
      read: false
    })
    created.push(order)
  })
  return created
}

// ── 首页汇总（对齐无纸化维保首页看板：今日到期/即将到期/保养超期/维保中/未确认/平台对接）──
function getHomeSummary() {
  ensureDueOrders()
  const today = formatTime().slice(0, 10)
  const soonEnd = formatTime(new Date(Date.now() + 3 * 86400000)).slice(0, 10)
  let dueToday = 0
  let dueSoon = 0
  let overdue = 0
  let inProgress = 0
  db.orders.forEach(function (o) {
    const planDay = (o.planTime || '').slice(0, 10)
    if (o.status === 'PROCESSING') inProgress++
    if (o.status !== 'DONE') {
      if (planDay === today) dueToday++
      else if (planDay > today && planDay <= soonEnd) dueSoon++
      else if (planDay && planDay < today) overdue++
    }
  })
  return {
    dueToday: dueToday,
    dueSoon: dueSoon,
    overdue: overdue,
    inProgress: inProgress,
    unconfirmed: db.unitRecords.filter(function (r) {
      return r.confirmStatus === 'PENDING'
    }).length,
    platformTotal: db.elevators.length, // 已对接监管平台的电梯总数
    openFaults: db.faults.filter(function (f) {
      return f.status === 'OPEN'
    }).length,
    // 年检预警：自行检查逾期未检台数（须在定期检验前完成，docs/01 §3.17）
    overdueInspects: listInspects().filter(function (i) {
      return i.status === '逾期未检'
    }).length,
    warnCount: dueToday + overdue
  }
}

function getElevator(id) {
  return db.elevators.find((e) => e.id === id) || null
}

function getElevatorByCode(code) {
  return db.elevators.find((e) => e.elevatorCode === code) || null
}

function getUseUnit(id) {
  return db.useUnits.find((u) => u.id === id) || null
}

// ── 电梯详细档案（docs/01 §3.4.1 + 平台 2.7 回填，docs/04 B.7）──
// 字段分两组标注来源：
//   platform: 平台 2.7 自动获取（8 字段；电话未脱敏，可直接回填用于 2.6 上报）
//   local:    平台不提供、本地维护（安装地址/经纬度/型号品牌/制造单位/下次检验日期等）
function getElevatorProfile(id) {
  const el = getElevator(id)
  if (!el) return null
  const unit = getUseUnit(el.useUnitId) || {}
  return {
    elevatorId: el.id,
    elevatorName: el.elevatorName,
    category: el.category || '',
    insideNumber: el.insideNumber || '',
    model: el.model || '',
    // 平台 2.7 自动获取
    platform: {
      syncedAt: el.platformSyncedAt || '',
      elevatorCode: el.elevatorCode || '',
      registrationCode: el.regCode || '',
      deviceCode: el.deviceCode || '',
      factoryNumber: el.factoryNumber || '',
      useUnitEntityId: el.useUnitEntityId || '',
      elevatorAdminister: el.elevatorAdminister || '',
      elevatorAdministerPhone: el.elevatorAdministerPhone || '',
      emergencyPhone: el.emergencyPhone || ''
    },
    // 本地维护（平台 2.7 不返回：使用单位名称/安装地址/经纬度/型号品牌/制造单位/下次检验日期）
    local: {
      projectName: unit.unitName || '',
      unitPrincipal: unit.unitPrincipal || '',
      address: el.location || '',
      lng: el.lng != null ? String(el.lng) : '',
      lat: el.lat != null ? String(el.lat) : '',
      brand: el.brand || '',
      manufacturer: el.manufacturer || '',
      productNo: el.productNo || '',
      driveMode: el.driveMode || '',
      ratedLoad: el.ratedLoad != null ? el.ratedLoad + (el.ratedLoadUnit || 'kg') : '',
      ratedSpeed: el.ratedSpeed != null ? el.ratedSpeed + (el.ratedSpeedUnit || 'm/s') : '',
      stationsDoors: el.stationsDoors || '',
      nextCheckDate: el.nextCheckDate || '',
      nextMaintenanceDate: el.nextMaintenanceDate || ''
    }
  }
}

// 电梯列表（工作台看板/市监局对接卡片下钻）
function listElevators() {
  return db.elevators.map(function (el) {
    const uu = getUseUnit(el.useUnitId) || {}
    return {
      id: el.id,
      elevatorName: el.elevatorName || '',
      elevatorCode: el.elevatorCode || '',
      deviceCode: el.deviceCode || '',
      regCode: el.regCode || '',
      model: el.model || '',
      projectName: getUseUnit(el.useUnitId) ? getUseUnit(el.useUnitId).unitName : ''
    }
  })
}

// 工单视图：附带电梯信息
function getOrder(id) {
  const o = db.orders.find((x) => x.id === id)
  if (!o) return null
  const view = Object.assign({}, o, { elevator: getElevator(o.elevatorId) })
  // 已完成工单附带完整维保记录视图数据（签字/确认状态，docs/01 §3.12.5 归档留痕）
  if (o.status === 'DONE' && o.originalRecordId) {
    const rec = db.unitRecords.find(function (r) { return r.originalRecordId === o.originalRecordId })
    if (rec) {
      view.recordInfo = {
        id: rec.id,
        shareToken: rec.shareToken || '',
        workerSignatureUrl: rec.workerSignatureUrl || '',
        assistantSignatureUrl: rec.assistantSignatureUrl || '',
        confirmStatus: rec.confirmStatus || '',
        satisfaction: rec.satisfaction
      }
    }
  }
  return view
}

function listOrders(query) {
  let list = db.orders.slice()
  // 首页统计卡下钻过滤：今日到期 / 即将到期(3天内) / 保养超期（仅未完成工单）
  if (query && query.due) {
    const today = formatTime().slice(0, 10)
    const soonEnd = formatTime(new Date(Date.now() + 3 * 86400000)).slice(0, 10)
    list = list.filter(function (o) {
      const day = (o.planTime || '').slice(0, 10)
      if (!day || o.status === 'DONE') return false
      if (query.due === 'today') return day === today
      if (query.due === 'soon') return day > today && day <= soonEnd
      if (query.due === 'overdue') return day < today
      return true
    })
  }
  if (query && query.status) {
    list = list.filter((o) => o.status === query.status)
  }
  if (query && query.keyword) {
    const k = String(query.keyword).toLowerCase()
    list = list.filter((o) => {
      const el = getElevator(o.elevatorId) || {}
      return (
        (o.orderNo || '').toLowerCase().indexOf(k) > -1 ||
        (el.elevatorName || '').toLowerCase().indexOf(k) > -1 ||
        (el.elevatorCode || '').toLowerCase().indexOf(k) > -1 ||
        (el.deviceCode || '').toLowerCase().indexOf(k) > -1 ||
        (el.regCode || '').toLowerCase().indexOf(k) > -1 ||
        (el.insideNumber || '').toLowerCase().indexOf(k) > -1
      )
    })
  }
  return list.map((o) => Object.assign({}, o, {
    elevatorName: (getElevator(o.elevatorId) || {}).elevatorName || ''
    // 工单卡片展示字段（对齐无纸化维保工单卡：设备代码/登记证号/内部编号/型号/项目名称）
    , elevatorCode: (getElevator(o.elevatorId) || {}).elevatorCode || ''
    , deviceCode: (getElevator(o.elevatorId) || {}).deviceCode || ''
    , regCode: (getElevator(o.elevatorId) || {}).regCode || ''
    , insideNumber: (getElevator(o.elevatorId) || {}).insideNumber || ''
    , model: (getElevator(o.elevatorId) || {}).model || ''
    , projectName: (getUseUnit((getElevator(o.elevatorId) || {}).useUnitId) || {}).unitName || ''
  }))
}

// 签到（body 对齐 docs/04 A.2：lng/lat/locationAccuracy/photoFileId/role/dynamicCode/collectedAt）
function markCheckin(orderId, body) {
  const o = db.orders.find((x) => x.id === orderId)
  if (!o) return null
  o.status = 'PROCESSING'
  // 离线补传场景保留本地原始采集时间
  o.checkinTime = (body && body.collectedAt) || formatTime()
  o.checkinExtra = {
    latitude: body && body.latitude,
    longitude: body && body.longitude,
    locationAccuracy: (body && body.locationAccuracy) || 0,
    role: (body && body.role) || 'PRINCIPAL',
    dynamicCode: (body && body.dynamicCode) || '',
    selfPhotoFileId: (body && body.photoFileId) || ''
  }
  return o
}

// 签退：生成维保记录 + 平台 2.6 上报快照（冻结档案字段，后续档案变更不回溯）
function markCheckout(orderId, body) {
  const o = db.orders.find((x) => x.id === orderId)
  if (!o) return null
  const el = getElevator(o.elevatorId) || {}
  const uu = getUseUnit(el.useUnitId) || {}

  o.status = 'DONE'
  o.checkoutTime = formatTime()
  o.originalRecordId = nextRecordId()
  o.reportStatus = 'SUBMITTED' // 签退即入上报队列（docs/04 A.2）
  o.duration = formatDuration(parseTime(o.checkoutTime) - parseTime(o.checkinTime))

  // 检查项明细与照片冻结
  const items = o.checklist.map((i) => Object.assign({}, i))
  const photos = []
  items.forEach((i) => {
    (i.photos || []).forEach((p) => photos.push(p))
  })
  const abnormalItems = items.filter((i) => i.result === 'ABNORMAL' && i.problemCode)
  const problemCodes = abnormalItems.length
    ? abnormalItems.map((i) => i.problemCode)
    : ['S0'] // 无隐患必须填 S0（平台 2.6）

  const record = {
    id: nextId('ur'),
    elevatorName: el.elevatorName || '',
    elevatorCode: el.elevatorCode || '',
    workType: o.workType,
    workTypeCode: o.workTypeCode,
    workerName: o.workerName,
    assistantName: o.assistantName,
    workerPlatformId: o.workerPlatformId,
    assistantPlatformId: o.assistantPlatformId,
    checkinTime: o.checkinTime,
    checkoutTime: o.checkoutTime,
    duration: o.duration,
    items,
    photos,
    workerSignatureUrl: (body && body.signatureUrl) || '',
    assistantSignatureUrl: (body && body.assistantSignatureUrl) || '',
    problemCodes,
    originalRecordId: o.originalRecordId,
    reportStatus: o.reportStatus,
    retryCount: 0,
    nextMaintenanceDate: addDays(WORK_TYPE_INTERVAL_DAYS[o.workTypeCode] || 15),
    confirmStatus: 'PENDING',
    satisfaction: null,
    signatureFileId: '',
    signatureUrl: ''
  }
  // 安全管理员签名确认链接令牌（链接可经微信分享远程签字，或维保人员本机代签）
  record.shareToken = 'sg' + Date.now().toString(36) + Math.floor(Math.random() * 1e8).toString(36)
  record.reportPayload = buildReportPayload(record, el, uu)
  db.unitRecords.unshift(record)
  return record
}

// 平台 2.6 报文快照（20 字段冻结，docs/04 B.6；含 workMeneger 拼写按规范原文）
function buildReportPayload(r, el, uu) {
  el = el || getElevatorByCode(r.elevatorCode) || {}
  uu = uu || getUseUnit(el.useUnitId) || {}
  const c = db.company
  // recorderPhone：按记录填写人姓名查人员档案（原实现硬编码张伟，双人/配合人员签退时会错）
  let recorderPhone = ''
  Object.keys(db.employees).forEach(function (k) {
    if (db.employees[k].name === r.workerName) recorderPhone = db.employees[k].phone
  })
  if (!recorderPhone) recorderPhone = (db.employees.WORKER || {}).phone || ''
  return {
    elevatorCode: r.elevatorCode,
    deviceCode: el.deviceCode || '',
    insideNumber: el.insideNumber || '',
    unitPrincipal: uu.unitPrincipal || '',
    unitPrincipalPhone: uu.unitPrincipalPhone || '',
    // 安全管理员/紧急电话：优先取平台 2.7 回填值（实测未脱敏，可直接回填 2.6 上报）
    elevatorAdminister: el.elevatorAdminister || uu.elevatorAdminister || '',
    elevatorAdministerPhone: el.elevatorAdministerPhone || uu.elevatorAdministerPhone || '',
    emergencyPhone: el.emergencyPhone || uu.emergencyPhone || '',
    workMenegerName: c.workMenegerName,
    workMenegerPhone: c.workMenegerPhone,
    workMan1Id: r.workerPlatformId || '',
    workMan2Id: r.assistantPlatformId || '', // 单人作业填法待平台确认（docs/06 #3）
    startTime: r.checkinTime,
    endTime: r.checkoutTime,
    workType: r.workTypeCode,
    recorder: r.workerName,
    recorderPhone: recorderPhone,
    originalRecordId: r.originalRecordId,
    problemCode: r.problemCodes,
    nextMaintenanceDate: r.nextMaintenanceDate
  }
}

function updateChecklistItem(orderId, itemId, patch) {
  const o = db.orders.find((x) => x.id === orderId)
  if (!o) return null
  const item = o.checklist.find((i) => i.id === itemId)
  if (!item) return null
  Object.assign(item, patch)
  return item
}

function createRescue(body) {
  // 救援节点耗时自动计算（docs/01 §3.9.3.1：系统自动计算并留痕各节点耗时，
  // 抵达超 30 分钟自动标记超时且记录不可删除；重庆为直辖市，法定时限 30 分钟）
  const arriveMin = body.arriveAt && body.alarmAt
    ? Math.round((parseTime(body.arriveAt) - parseTime(body.alarmAt)) / 60000)
    : null
  const rescuedMin = body.rescuedAt && body.alarmAt
    ? Math.round((parseTime(body.rescuedAt) - parseTime(body.alarmAt)) / 60000)
    : null
  const overtime = arriveMin != null && arriveMin > 30
  const r = {
    id: nextId('rs'),
    elevatorCode: body.elevatorCode,
    trappedCount: body.trappedCount || 0,
    desc: body.desc || '',
    alarmAt: body.alarmAt || '',
    departAt: body.departAt || '',
    arriveAt: body.arriveAt || '',
    rescuedAt: body.rescuedAt || '',
    arriveMinutes: arriveMin,
    rescuedMinutes: rescuedMin,
    overtime: overtime, // 超时记录不可删除（法定留痕）
    reason: body.reason || '',
    action: body.action || '',
    status: body.rescuedAt ? '已解除' : '处理中',
    createdAt: formatTime()
  }
  db.rescues.unshift(r)
  return r
}

function createFault(body) {
  const f = {
    id: nextId('ft'),
    elevatorCode: body.elevatorCode,
    faultType: body.faultType || '',
    desc: body.desc || '',
    status: 'OPEN',
    handleDesc: '',
    createdAt: formatTime()
  }
  db.faults.unshift(f)
  return f
}

function closeFault(id, body) {
  const f = db.faults.find((x) => x.id === id)
  if (!f) return null
  f.status = 'CLOSED'
  f.handleDesc = (body && body.handleDesc) || ''
  return f
}

// ── 应急演练（docs/01 §3.18）──
function listDrills() {
  // 覆盖检查：近半年内，本单位在保的每个电梯品种均须有演练记录
  const halfYearAgo = Date.now() - 182 * 86400000
  const covered = []
  db.drills.forEach(function (r) {
    const t = parseTime(r.drillDate)
    if (t >= halfYearAgo && covered.indexOf(r.category) === -1) covered.push(r.category)
  })
  const all = []
  db.elevators.forEach(function (el) {
    if (el.category && all.indexOf(el.category) === -1) all.push(el.category)
  })
  const missing = all.filter(function (c) {
    return covered.indexOf(c) === -1
  })
  return { list: db.drills.slice(), coverage: { covered: covered, missing: missing } }
}

function createDrill(body) {
  if (!body.drillDate) throw { code: 422, message: '请填写演练日期' }
  if (!body.category) throw { code: 422, message: '请选择电梯品种' }
  const r = {
    id: nextId('dr'),
    drillDate: body.drillDate,
    category: body.category,
    scene: body.scene || '',
    participants: body.participants || '',
    process: body.process || '',
    problems: body.problems || '',
    actions: body.actions || '',
    createdAt: formatTime()
  }
  db.drills.unshift(r)
  return r
}

// ── 自行检查（docs/01 §3.17，独立记录类型 inspect_record，不触发 2.6 上报）──
function listInspects() {
  const now = Date.now()
  return db.elevators.map(function (el) {
    const record = db.inspects.find(function (i) {
      return i.elevatorId === el.id
    })
    const nextCheck = parseTime(el.nextCheckDate)
    let status = '未检'
    if (record) {
      status = '已完成'
    } else if (nextCheck && nextCheck - now < 30 * 86400000) {
      // 法定要求：须在下次定期检验之前完成，临近/超过即逾期预警
      status = '逾期未检'
    }
    return {
      elevatorId: el.id,
      elevatorName: el.elevatorName,
      elevatorCode: el.elevatorCode,
      category: el.category || '',
      nextCheckDate: el.nextCheckDate || '',
      lastInspectDate: record ? record.inspectDate : '',
      status: status
    }
  })
}

function createInspect(body) {
  const items = body.items || []
  const unmarked = items.filter(function (i) {
    return !i.result
  })
  if (unmarked.length) throw { code: 422, message: '尚有 ' + unmarked.length + ' 项未填写检查结果' }
  const abnormal = items.filter(function (i) {
    return i.result === 'ABNORMAL'
  })
  const noDesc = abnormal.filter(function (i) {
    return !i.abnormalDesc
  })
  if (noDesc.length) throw { code: 422, message: '不合格项请填写问题描述' }
  if (!body.inspectorSign) throw { code: 422, message: '请完成检查人员签字' }
  if (!body.reviewerSign) throw { code: 422, message: '请完成审核人员签字' }
  const r = {
    id: nextId('in'),
    elevatorId: body.elevatorId,
    inspectDate: formatTime().slice(0, 10),
    itemTotal: items.length,
    abnormalCount: abnormal.length,
    problems: abnormal.map(function (i) {
      return i.name + '：' + i.abnormalDesc
    }).join('；'),
    inspectorSign: body.inspectorSign,
    reviewerSign: body.reviewerSign
  }
  db.inspects.unshift(r)
  return r
}

// 初始演示记录补挂平台 2.6 报文快照
db.unitRecords.forEach((r) => {
  const el = getElevatorByCode(r.elevatorCode) || {}
  r.reportPayload = buildReportPayload(r, el, getUseUnit(el.useUnitId))
})

module.exports = {
  db,
  APPENDIX_TPLS,
  FREQ_CHAIN,
  FREQ_LABELS,
  CATEGORY_APPENDIX,
  buildChecklist,
  makeDoneItems,
  nextId,
  nextRecordId,
  ensureDueOrders,
  getHomeSummary,
  listElevators,
  getElevator,
  getElevatorByCode,
  getUseUnit,
  getElevatorProfile,
  getOrder,
  listOrders,
  markCheckin,
  markCheckout,
  buildReportPayload,
  updateChecklistItem,
  listDrills,
  createDrill,
  listInspects,
  createInspect,
  createRescue,
  createFault,
  closeFault
}
