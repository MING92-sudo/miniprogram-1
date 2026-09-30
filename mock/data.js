// Mock 数据仓库（仅 config.useMock=true 时生效；后端就绪后可整体删除 mock/ 目录）
// 数据为内存态：小程序冷启动后重置，一次会话内保持状态流转
const { formatTime, formatDuration, parseTime } = require('../utils/util')
const { APPENDIX_A_TPL, FREQ_CHAIN, FREQ_LABEL } = require('./checklist-template')

// 演示用网络图片（正式环境为 COS 文件 url）
const DEMO_PHOTO = 'https://picsum.photos/seed/em-elev/600/450'
const DEMO_SIGNATURE = 'https://picsum.photos/seed/em-sig/480/180'

// 按维保频次生成 TSG 附件A 累加式检查清单（docs/01 §3.7.3）：
// HM=表A-1(31项)；TM=+表A-2(44)；SM=+表A-3(59)；OY=+表A-4(76)；FM 按需取半月基础表
// 周期性/季节性条目（execCycleMonth/ageCondition/seasonWindow）默认"本次无需执行"，
// 灰显不计入未完成项，允许人工点选"本次仍要执行"（docs/03 §6.1）
function buildChecklist(workTypeCode) {
  const chain = FREQ_CHAIN[workTypeCode] || FREQ_CHAIN.HM
  const items = []
  chain.forEach(function (freq) {
    APPENDIX_A_TPL[freq].forEach(function (tpl) {
      const periodic = !!(tpl.execCycleMonth || tpl.ageCondition || tpl.seasonWindow)
      items.push({
        id: 'ci_' + tpl.itemCode,
        itemCode: tpl.itemCode, // 附件-频次-序号（如 A-1-29）
        seq: tpl.seq,
        freq: tpl.freq,
        freqLabel: FREQ_LABEL[freq],
        name: tpl.name,
        requirement: tpl.requirement, // TSG 原文"维护保养基本要求"
        judgeType: tpl.judgeType,
        valueMin: tpl.valueMin,
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
function makeDoneItems(workTypeCode, withAbnormal) {
  const items = buildChecklist(workTypeCode)
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
    const item = items.find(function (i) { return i.itemCode === 'A-1-18' })
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
    workMenegerPhone: '137****2001'
  },

  // 使用单位档案（平台 2.6 冻结字段来源：unitPrincipal/elevatorAdminister/emergencyPhone）
  useUnits: [
    { id: 'uu_1', unitName: '重庆世纪物业管理有限公司', unitPrincipal: '刘建国', unitPrincipalPhone: '139****1001', elevatorAdminister: '王芳', elevatorAdministerPhone: '138****0003', emergencyPhone: '023-6761****' },
    { id: 'uu_2', unitName: '重庆蓝湾物业服务有限公司', unitPrincipal: '周涛', unitPrincipalPhone: '139****3002', elevatorAdminister: '吴静', elevatorAdministerPhone: '138****3005', emergencyPhone: '023-6799****' }
  ],

  // 演示账号（正式版由 /auth/wx-login + 绑定流程产生）；platformId 为平台人员ID（2.5 同步产物）
  employees: {
    WORKER: { id: 'emp_1', name: '张伟', phone: '138****0001', role: 'WORKER', roleText: '维保人员', platformId: '990001' },
    LEADER: { id: 'emp_2', name: '陈刚', phone: '138****0002', role: 'LEADER', roleText: '班组长', platformId: '990002' },
    UNIT_ADMIN: { id: 'unit_1', name: '王芳', phone: '138****0003', role: 'UNIT_ADMIN', roleText: '使用单位安全管理员', platformId: '' }
  },

  elevators: [
    { id: 'el_1', elevatorCode: 'EM-2024-001', elevatorName: '世纪大厦 1# 客梯', location: '渝北区龙山一路 88 号世纪大厦', regCode: 'TSCQ5001120001', deviceCode: 'DT-CQ-2024-001', insideNumber: 'KT-01', useUnitId: 'uu_1' },
    { id: 'el_2', elevatorCode: 'EM-2024-002', elevatorName: '世纪大厦 2# 客梯', location: '渝北区龙山一路 88 号世纪大厦', regCode: 'TSCQ5001120002', deviceCode: 'DT-CQ-2024-002', insideNumber: 'KT-02', useUnitId: 'uu_1' },
    { id: 'el_3', elevatorCode: 'EM-2024-003', elevatorName: '蓝湾国际 A 座货梯', location: '江北区滨江路 6 号蓝湾国际', regCode: 'TSCQ5001120003', deviceCode: 'DT-CQ-2024-003', insideNumber: 'HT-01', useUnitId: 'uu_2' }
  ],

  orders: [
    {
      id: 'wo_1', orderNo: 'WO20260929-001', elevatorId: 'el_1',
      workType: '半月维保', workTypeCode: 'HM', planTime: today('09:00:00'), status: 'PENDING',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: '', checkoutTime: '', duration: '', originalRecordId: '', reportStatus: '',
      checklist: buildChecklist('HM')
    },
    {
      id: 'wo_2', orderNo: 'WO20260929-002', elevatorId: 'el_3',
      workType: '救援后复查', workTypeCode: 'FM', planTime: today('08:30:00'), status: 'PROCESSING',
      workerName: '张伟', assistantName: '', workerPlatformId: '990001', assistantPlatformId: '',
      // 签到时间留足 30 分钟作业时长下限（constants MIN_WORK_DURATION_MINUTES），保证演示可直接签退
      checkinTime: today('08:00:00'), checkoutTime: '', duration: '', originalRecordId: '', reportStatus: '',
      checklist: buildChecklist('FM')
    },
    {
      id: 'wo_3', orderNo: 'WO20260928-011', elevatorId: 'el_2',
      workType: '半月维保', workTypeCode: 'HM', planTime: today('09:00:00'), status: 'DONE',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('08:52:00'), checkoutTime: today('11:20:00'), duration: '02:28:00',
      originalRecordId: '19480012609000011', reportStatus: 'REPORTED',
      checklist: makeDoneItems('HM', true)
    },
    {
      id: 'wo_4', orderNo: 'WO20260927-008', elevatorId: 'el_3',
      workType: '困人救援', workTypeCode: 'FM', planTime: today('16:30:00'), status: 'DONE',
      workerName: '张伟', assistantName: '', workerPlatformId: '990001', assistantPlatformId: '',
      checkinTime: today('16:28:00'), checkoutTime: today('18:05:00'), duration: '01:37:00',
      originalRecordId: '19480012609000008', reportStatus: 'REPORTED',
      checklist: makeDoneItems('FM', false)
    },
    {
      id: 'wo_5', orderNo: 'WO20260926-005', elevatorId: 'el_1',
      workType: '年度维保', workTypeCode: 'OY', planTime: today('09:00:00'), status: 'DONE',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('09:02:00'), checkoutTime: today('15:40:00'), duration: '06:38:00',
      originalRecordId: '19480012609000005', reportStatus: 'REPORTED',
      checklist: makeDoneItems('OY', false)
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

  // 使用单位待确认记录（签退时冻结的维保记录快照）
  unitRecords: [
    {
      id: 'ur_1', elevatorName: '世纪大厦 1# 客梯', elevatorCode: 'EM-2024-001',
      workType: '半月维保', workTypeCode: 'HM',
      workerName: '张伟', assistantName: '李强', workerPlatformId: '990001', assistantPlatformId: '990003',
      checkinTime: today('08:52:00'), checkoutTime: today('11:20:00'), duration: '02:28:00',
      items: makeDoneItems('HM', true), photos: [DEMO_PHOTO],
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
      items: makeDoneItems('FM', false), photos: [],
      workerSignatureUrl: DEMO_SIGNATURE,
      problemCodes: ['S0'], // 无隐患必须填 S0（平台 2.6 约定）
      originalRecordId: '19480012609000008', reportStatus: 'REPORTED', retryCount: 0,
      nextMaintenanceDate: addDays(30),
      confirmStatus: 'CONFIRMED', satisfaction: 5, signatureFileId: 'mock_file_sig1', signatureUrl: DEMO_SIGNATURE
    }
  ],

  knowledge: [
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
      content: '一、维保记录（2.4）：originalRecordId 为平台侧记录唯一标识，幂等性未确认前失败不自动重试（REG_RETRY_AUTO=false）。\n\n二、人员同步（2.2）：维保人员需先同步获取 platform_id 才可上报（错误码 1004）。\n\n三、存量记录（2.8）：临时接口，平台关闭后停止推送。\n\n四、签到定位（2.4）：坐标系为 WGS84，位置超阈由后端判定（错误码 1001）。\n\n五、上报重试：30s/2m/10m 三级退避，仅网络类错误触发。'
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

function getElevator(id) {
  return db.elevators.find((e) => e.id === id) || null
}

function getElevatorByCode(code) {
  return db.elevators.find((e) => e.elevatorCode === code) || null
}

function getUseUnit(id) {
  return db.useUnits.find((u) => u.id === id) || null
}

// 工单视图：附带电梯信息
function getOrder(id) {
  const o = db.orders.find((x) => x.id === id)
  if (!o) return null
  return Object.assign({}, o, { elevator: getElevator(o.elevatorId) })
}

function listOrders(query) {
  let list = db.orders.slice()
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
        (el.elevatorCode || '').toLowerCase().indexOf(k) > -1
      )
    })
  }
  return list.map((o) => Object.assign({}, o, {
    elevatorName: (getElevator(o.elevatorId) || {}).elevatorName || ''
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
  record.reportPayload = buildReportPayload(record, el, uu)
  db.unitRecords.unshift(record)
  return record
}

// 平台 2.6 报文快照（20 字段冻结，docs/04 B.6；含 workMeneger 拼写按规范原文）
function buildReportPayload(r, el, uu) {
  el = el || getElevatorByCode(r.elevatorCode) || {}
  uu = uu || getUseUnit(el.useUnitId) || {}
  const c = db.company
  return {
    elevatorCode: r.elevatorCode,
    deviceCode: el.deviceCode || '',
    insideNumber: el.insideNumber || '',
    unitPrincipal: uu.unitPrincipal || '',
    unitPrincipalPhone: uu.unitPrincipalPhone || '',
    elevatorAdminister: uu.elevatorAdminister || '',
    elevatorAdministerPhone: uu.elevatorAdministerPhone || '',
    emergencyPhone: uu.emergencyPhone || '',
    workMenegerName: c.workMenegerName,
    workMenegerPhone: c.workMenegerPhone,
    workMan1Id: r.workerPlatformId || '',
    workMan2Id: r.assistantPlatformId || '', // 单人作业填法待平台确认（docs/06 #3）
    startTime: r.checkinTime,
    endTime: r.checkoutTime,
    workType: r.workTypeCode,
    recorder: r.workerName,
    recorderPhone: (db.employees.WORKER || {}).phone || '',
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
  const r = {
    id: nextId('rs'),
    elevatorCode: body.elevatorCode,
    trappedCount: body.trappedCount || 0,
    desc: body.desc || '',
    status: '处理中',
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

// 初始演示记录补挂平台 2.6 报文快照
db.unitRecords.forEach((r) => {
  const el = getElevatorByCode(r.elevatorCode) || {}
  r.reportPayload = buildReportPayload(r, el, getUseUnit(el.useUnitId))
})

module.exports = {
  db,
  nextId,
  nextRecordId,
  getElevator,
  getElevatorByCode,
  getUseUnit,
  getOrder,
  listOrders,
  markCheckin,
  markCheckout,
  buildReportPayload,
  updateChecklistItem,
  createRescue,
  createFault,
  closeFault
}
