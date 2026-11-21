// Mock 拦截器：按 method + 路径模板匹配 services 层请求，返回统一响应的 data 部分
// 路径模板支持 :id 形式参数；handler 内 return 即业务成功，throw {code,message} 即业务失败
const d = require('./data')
const { formatTime, parseTime, distanceMeters } = require('../utils/util')
const config = require('../config/index')
const menus = require('../config/menus')

// mock 无 JWT 解析：登录成功后记住当前演示角色，供自助改密等"当前用户"接口使用
let currentRole = 'WORKER'
// M2 单账号多角色：当前会话角色集（登录写入），/me/menus 投影用
let currentRoles = ['MAINT_TECH']
// 统一登录客户分支：当前客户账号（clientAccounts 行），员工会话为 null
let currentClient = null
// 运行时重载后从本地会话恢复当前角色（与生产 JWT 解析同口径：会话持久，菜单按会话角色投影）
try {
  const sr = wx.getStorageSync('em_roles')
  if (sr && sr.length) currentRoles = sr
  const cr = wx.getStorageSync('em_role')
  if (cr) currentRole = cr
} catch (e) { /* 非小程序环境忽略 */ }
// 权限投影复用 constants/permission-matrix.js 镜像（与后端 PermissionMatrix 同源，Y∪C；防手写漂移）
const permMirror = require('../constants/permission-matrix')
function permsOf(roles) {
  const set = {}
  roles.forEach((r) => permMirror.canonicalCodes().forEach((code) => {
    if (permMirror.state(r, code) !== 'DENY') set[code] = true
  }))
  return Object.keys(set)
}
// ── P1 T7：客户主体（使用单位）判定 / 门禁 / 字段白名单，逐条对齐后端 ──
// 对应后端：RoleCatalog.isCustomerPrincipal、UnitRecordService（requireClient/requireOwnUnit/
// requireViewable/requireSignable/toClientMap）、RecordLifecycleService.refuseByToken、UnitPortalService。
// 客户主体 = 角色集非空且全部规范码为 CUSTOMER（CLIENT_ADMIN/CLIENT_SIGNER；旧码 UNIT_ADMIN 经根 ALIAS 归一）
function isCustomerPrincipal(roles) {
  const list = roles || []
  if (!list.length) return false
  return list.every((r) => {
    const c = permMirror.canonical(r)
    return c === 'CLIENT_ADMIN' || c === 'CLIENT_SIGNER'
  })
}
// 登录体 principalType（后端 RoleCatalog.PrincipalType，T8 小程序消费）
function principalTypeOf(roles) {
  return isCustomerPrincipal(roles) ? 'CUSTOMER' : 'EMPLOYEE'
}
// CLIENT 角色本单位锚点（use_unit.id）；非客户返回 undefined，客户无锚点返回 null（fail-closed）
function clientUnitAnchor() {
  if (!isCustomerPrincipal(currentRoles)) return undefined
  if (currentClient) return currentClient.useUnitId || null
  const u = d.db.employees[currentRole]
  return (u && u.useUnitId) || null
}
// 客户可见生命周期集合（docs/14 §4.6 可见性表，后端 CLIENT_VISIBLE_LIFECYCLES）：待签可签 + 三态只读
const CLIENT_VISIBLE_LIFECYCLES = ['PENDING_CLIENT_SIGNATURE', 'SIGNED', 'ARCHIVED', 'REFUSED_RETURNED']
// 记录归属单位（后端 UnitRecordService.unitOf 同口径：按电梯当前 useUnitId）
function recordUnitId(r) {
  const el = d.getElevatorByCode(r.elevatorCode)
  return (el && el.useUnitId) || ''
}
// 本单位电梯编码集（后端 UnitPortalService.ownCodes；停用梯不计）
function ownElevatorCodes(unitId) {
  const codes = new Set()
  ;(d.db.elevators || []).forEach((el) => {
    if (el.useUnitId === unitId && el.status !== 'INACTIVE' && el.elevatorCode) codes.add(el.elevatorCode)
  })
  return codes
}
// 链末端最新版本（后端 isLatestVersion）：不存在 prevVersionId = 本记录 id 的后续版本
function isLatestVersion(r) {
  return !(d.db.unitRecords || []).some((x) => x.prevVersionId && x.prevVersionId === r.id)
}
// 可签集合（后端 pendingAs 谓词）：唯一可签状态 + confirmStatus=PENDING + 最新版本
function isSignableRow(r) {
  return r.lifecycleStatus === 'PENDING_CLIENT_SIGNATURE' && r.confirmStatus === 'PENDING' && isLatestVersion(r)
}
// 签字门禁链（后端 requireSignable）：任一失败 422 且无副作用
function requireSignable(r) {
  if (r.lifecycleStatus !== 'PENDING_CLIENT_SIGNATURE') throw { code: 422, message: '记录不在待使用单位签署状态' }
  if (r.confirmStatus !== 'PENDING') throw { code: 422, message: '记录已签署，不能重复签字' }
  if (!isLatestVersion(r)) throw { code: 422, message: '记录已被新版本替代，请签署最新记录' }
}
// 查看门禁（后端 requireViewable）：可见 ≠ 可签，历史版本仅内部/审计可见
function requireViewable(r) {
  if (CLIENT_VISIBLE_LIFECYCLES.indexOf(r.lifecycleStatus) < 0) throw { code: 422, message: '记录当前状态客户不可见' }
  if (!isLatestVersion(r)) throw { code: 422, message: '记录已被新版本替代，请查看最新记录' }
}
// 门禁链①②（后端 requireClient）：非客户主体 / 无单位锚点一律 1403 fail-closed
function requireClientUnit() {
  if (!isCustomerPrincipal(currentRoles)) throw { code: 1403, message: '仅使用单位可访问门户记录' }
  const unitId = clientUnitAnchor()
  if (!unitId) throw { code: 1403, message: '缺少单位锚点' }
  return unitId
}
// 门户数据域锚点（后端 UnitPortalController.unitId）：非客户 / 无锚点 403，绝不回退 useUnits[0] 或全量
function requirePortalUnit() {
  const unitId = isCustomerPrincipal(currentRoles) ? clientUnitAnchor() : null
  if (!unitId) throw { code: 403, message: '当前账号无使用单位数据权限' }
  return unitId
}
// 客户字段白名单投影（后端 UnitRecordService.toClientMap 等价，docs/14 §4.7）：明确不返回
// reportPayload/context(previewContext)/shareToken/originalRecordId/workerPlatformId/assistantPlatformId/
// refuseReason(内部复核)/reportStatus/uploadStatus/retryCount/satisfaction；items 同为白名单四字段
function toClientRecord(r) {
  return {
    id: r.id,
    version: r.version != null ? r.version : 1,
    prevVersionId: r.prevVersionId || '',
    lifecycleStatus: r.lifecycleStatus || '',
    elevatorCode: r.elevatorCode || '', elevatorName: r.elevatorName || '',
    workType: r.workType || '', workTypeCode: r.workTypeCode || '',
    workerName: r.workerName || '', assistantName: r.assistantName || '',
    checkinTime: r.checkinTime || '', checkoutTime: r.checkoutTime || '', duration: r.duration || '',
    items: (r.items || []).map((i) => ({ name: i.name, result: i.result,
      value: i.value != null ? i.value : (i.valueText || null), photos: i.photos || [] })),
    photos: r.photos || [],
    problemCodes: r.problemCodes || [],
    todoDesc: r.todoDesc || '',
    workerSignatureUrl: r.workerSignatureUrl || '', assistantSignatureUrl: r.assistantSignatureUrl || '',
    confirmStatus: r.confirmStatus || 'PENDING',
    signatureUrl: r.signatureUrl || '',
    pdfUrl: r.archiveFileId ? 'https://mock/files/' + r.archiveFileId + '?s=mock' : '',
    // mock 演示字段（原型「记录版本 / SHA-256 前 8 位」展示；后端 P1 无对应列，签名链接页同源）
    recordVersion: r.recordVersion || '', hashPrefix: r.hashPrefix || '', frozenAt: r.frozenAt || ''
  }
}
// V2.80 签字 SLA（后端 ClientSignSlaService 同口径）：起算=signRequestedAt（回退 checkoutTime/createdAt），
// 默认 72h；待签行/签字页倒计时/超期标记共用
const CLIENT_SIGN_SLA_HOURS = 72
function signSlaFields(r) {
  const base = r.signRequestedAt || r.checkoutTime || r.createdAt || ''
  if (!base) return { signRequestedAt: '', signDeadline: '', overdue: false }
  const deadline = formatTime(new Date(parseTime(base) + CLIENT_SIGN_SLA_HOURS * 3600000))
  return { signRequestedAt: base, signDeadline: deadline, overdue: parseTime(deadline) < Date.now() }
}
// V2.80 拒签原因分类码（后端 RecordLifecycleService.REFUSE_CODES 镜像同步，改动必须双侧同改）
const REFUSE_CODES = {
  R1: '记录内容与现场不符', R2: '维保项目漏项或未按单执行',
  R3: '现场照片不清晰或与实际不符', R4: '时间或人员信息有误', R5: '其他'
}
// 列表行投影（后端 UnitPortalService.recordRow 等价）：含 lifecycleStatus/version，不含 shareToken 等凭证
function clientRecordRow(r) {
  return Object.assign({
    id: r.id, elevatorCode: r.elevatorCode || '', elevatorName: r.elevatorName || '',
    workType: r.workType || '', workerName: r.workerName || '', assistantName: r.assistantName || '',
    checkinTime: r.checkinTime || '', checkoutTime: r.checkoutTime || '', duration: r.duration || '',
    confirmStatus: r.confirmStatus || 'PENDING', lifecycleStatus: r.lifecycleStatus || '',
    version: r.version != null ? r.version : 1
  }, signSlaFields(r))
}
// P1 兼容期例外（控制器裁决 T7-C2，docs/14 §4.6 规则 8「可保留既有 shareToken 供旧链接」）：
// 客户待办「去签字」以行内 shareToken 拼签字链接（pkg-client/pages/todo goSign），而 sign 页只有 token
// 路径、无会话回退，去掉即断签字链路 → 仅 /unit/pending-signs 行兼容期保留 shareToken；
// /unit/records/list、/unit/records/pending 与记录详情一律不含。后端 recordRow 同步由 T8 补齐（本轮接受暂时差异）。
function clientPendingSignRow(r) {
  return Object.assign(clientRecordRow(r), { shareToken: r.shareToken || '' })
}
// 客户可见记录集（后端 UnitPortalService.records）：本单位 + 可见集合 + 链末端最新版本，签退时间倒序
function clientVisibleRecords(unitId) {
  const codes = ownElevatorCodes(unitId)
  return (d.db.unitRecords || [])
    .filter((r) => codes.has(r.elevatorCode) && CLIENT_VISIBLE_LIFECYCLES.indexOf(r.lifecycleStatus) > -1 && isLatestVersion(r))
    .sort((a, b) => ((a.checkoutTime || '') < (b.checkoutTime || '') ? 1 : -1))
    .slice(0, 50)
}
// 客户待签角标（后端 MiniappMenuService.clientPendingSigns 同口径）：无锚点 fail-closed 计 0，不回退全表
function clientPendingSignCount() {
  const unitId = clientUnitAnchor()
  if (!unitId) return 0
  const codes = ownElevatorCodes(unitId)
  return (d.db.unitRecords || []).filter((r) => codes.has(r.elevatorCode) && isSignableRow(r)).length
}
// 签字落库（后端 UnitRecordService.signRecord 同口径，confirmAs/confirmByToken 共用，门禁须已前置通过）：
// 先签为准（条件更新等价判定，已签 → 422，不覆盖首签）；satisfaction 一律不接收（docs/14 §4.7 评价关闭）
function signRecord(r, body) {
  const signatureUrl = String((body && body.signatureUrl) || '')
  const signatureFileId = String((body && body.signatureFileId) || '')
  if (!signatureUrl && !signatureFileId) throw { code: 422, message: '请先完成签名' }
  if (r.confirmStatus !== 'PENDING' || r.lifecycleStatus !== 'PENDING_CLIENT_SIGNATURE') {
    throw { code: 422, message: '记录已被签署（先签为准），请刷新后查看' }
  }
  r.confirmStatus = 'CONFIRMED'
  r.signatureFileId = signatureFileId
  r.signatureUrl = signatureUrl
  r.lifecycleStatus = 'SIGNED' // 后端 onClientSigned：SIGNED → 自动归档（mock 不生成 PDF）
  return { ok: true }
}
// 已裁合规矩阵台账模块总开关（docs/01 §10.2，2026-11-21 对齐后端 LegacyModuleInterceptor）：
// /rescues /drills /inspects /knowledge 共 9 条端点默认 403；恢复交付时连同后端 APP_LEGACY_MODULES_ENABLED 一起打开
const LEGACY_MODULES_ENABLED = false
function requireLegacyModule() {
  if (!LEGACY_MODULES_ENABLED) throw { code: 403, message: '该模块已裁剪停用（docs/01 §10.2），恢复需开启 APP_LEGACY_MODULES_ENABLED' }
}
// 部门互斥读门禁（与后端 MultiRoleGate.requireRead 同口径，2026-11-15 对齐）
function requireRead(code) {
  if (!currentRoles.some((r) => permMirror.state(r, code) !== 'DENY')) {
    throw { code: 1403, message: '无权访问该资源' }
  }
}
// 写门禁（与后端 MultiRoleGate.requireWrite 同口径：仅 Y 放行，C 是条件读不算写）
function requireWrite(code) {
  if (!currentRoles.some((r) => permMirror.state(r, code) === 'Y')) {
    throw { code: 1403, message: '无权限: ' + code }
  }
}
// C-24：V7 班组读谓词镜像（后端 EmployeeScopeService.visibleReadPlatformIds，按主角色 role 旧码判定）：
// ADMIN（平台）与 MAINT_MANAGER/MAINT_QUALITY（维保管理）→ null 不限；
// 安装部三岗与 MAINT_BIZ（非现场岗）→ 空集（任何记录 1403）；其余 → 本人 + LEADER 本班组。
// 与后端同缺口径：规范码主角色（如 MAINT_MGR/PLATFORM_ADMIN）不落旧码集合 → 走本人兜底（fail-closed）；
// mock 员工无 regionId 概念，区域分支省略（mock 数据未分区，与后端"未分区按原口径"一致）。
function visibleReadPlatformIds() {
  const emp = d.db.employees[currentRole] || {}
  const role = emp.role || ''
  if (['ADMIN', 'MAINT_MANAGER', 'MAINT_QUALITY'].indexOf(role) > -1) return null
  if (['INSTALL_MANAGER', 'INSTALL_CLERK', 'INSTALL_WORKER', 'MAINT_BIZ'].indexOf(role) > -1) return new Set()
  const ids = new Set()
  if (role === 'LEADER' && emp.groupName) {
    Object.keys(d.db.employees).forEach((k) => {
      const m = d.db.employees[k]
      if (m.groupName === emp.groupName && m.platformId) ids.add(m.platformId)
    })
  }
  if (!ids.size && emp.platformId) ids.add(emp.platformId)
  return ids
}
// 记录归属校验（后端 RecordController 同谓词，WorkOrderService.scopeOrders 同款）：
// null=管理角色不限；非 null 须命中主维保或配合人员 platformId，越权 1403 无副作用；空集任何记录 1403
function requireRecordScope(r) {
  const ids = visibleReadPlatformIds()
  if (ids === null) return
  if (!ids.has(r.workerPlatformId) && !ids.has(r.assistantPlatformId)) {
    throw { code: 1403, message: '仅可查看本人或本班组记录' }
  }
}
// 当前会话员工（mock 无 JWT 解析：登录时记录的演示账号，等价后端 scopeService.require(empId)）
function currentEmployee() {
  return d.db.employees[currentRole] || {}
}
// 区域抢单池读谓词（2026-11-20 设计 §4.2 / M9/M11，后端 WorkOrderService.scopeOrders+poolVisible 同口径）：
// 管理角色（ids==null）不限；已认领工单按主/配合人 platformId（V7 班组集合）放行；
// 未认领 PENDING 池工单仅对持平台ID（D4）且 工单 regionId 快照为空（不限区域）或等于员工区域 的账号可见
function orderVisible(o) {
  const ids = visibleReadPlatformIds()
  if (ids === null) return true
  if (o.workerPlatformId && ids.has(o.workerPlatformId)) return true
  if (o.assistantPlatformId && ids.has(o.assistantPlatformId)) return true
  const me = currentEmployee()
  if (o.status === 'PENDING' && !o.workerPlatformId && !o.workerName && me.platformId &&
      (o.regionId == null || o.regionId === '' || o.regionId === me.regionId)) return true
  return false
}
// 配合人候选谓词（后端 WorkOrderService.partnerEligible 同口径）：
// 同区域快照（空=不限）∧ 持平台ID ∧ 在职 ∧ 排除主维保本人
function partnerEligible(o, e) {
  if (!e || !e.platformId) return false
  if (e.enabled === false) return false
  if (e.platformId === o.workerPlatformId) return false
  return o.regionId == null || o.regionId === '' || o.regionId === e.regionId
}
// 移交/外接建档 mock 状态（模块级，进程重启即清空）
const mockHandovers = []
const mockFilings = []

// 自动排期触发点：真实后端为定时任务（docs/04 A.10.9），读接口一律不触发派单；
// mock 无调度器，仅在模块加载时执行一次，读接口不得再调用 ensureDueOrders
d.ensureDueOrders()

// 分页（page 从 1 开始，size 默认 20，与接口文档 A.0 约定一致）
function paginate(list, query) {
  const page = Math.max(1, Number(query && query.page) || 1)
  const size = Math.max(1, Number(query && query.size) || 20)
  return {
    list: list.slice((page - 1) * size, page * size),
    total: list.length
  }
}

function findOr404(list, id, name) {
  const item = list.find((x) => x.id === id)
  if (!item) throw { code: 1404, message: (name || '记录') + '不存在' }
  return item
}

// C-25⑧ 匿名 token 端点归一（后端 UnitRecordService/RecordLifecycleService.findOr401 同口径）：
// sign-view/confirm-by-token/refuse-by-token 记录不存在时与错 token 一致抛 401 同文案，
// 匿名探测者无法据 1404/401 区分记录 id 存在性；内部认证路径仍用 findOr404（1404）
function findOr401Token(list, id) {
  const item = list.find((x) => x.id === id)
  if (!item) throw { code: 401, message: '确认链接无效或已失效' }
  return item
}

// C-12：GB/T 10060—2023 表1 ☆重要项（49 项）与试验项（57—74），与后端 HandoverChecklistTemplate 对齐
const HANDOVER_IMPORTANT = new Set([2, 3, 5, 8, 9, 10, 12, 13, 16, 18, 20, 21, 22, 23, 24, 25, 26, 29, 30, 32, 33, 35, 36, 37, 38, 39, 42, 43, 45, 46, 47, 48, 49, 50, 51, 52, 53, 58, 62, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73])
function buildHandoverChecklist() {
  const items = []
  for (let no = 1; no <= 74; no++) {
    items.push({ id: 'hi_' + no, itemNo: no, itemGroup: 'G' + String(Math.min(13, Math.ceil(no / 6))).padStart(2, '0'),
      itemName: '检查项 ' + no, important: HANDOVER_IMPORTANT.has(no), result: null,
      measuredValue: null, failDesc: null, naReason: null, photoFileIds: null })
  }
  return items
}
// 7.2/7.3 五路径判定（与后端 HandoverChecklistService.judge 同口径）
function judgeHandover(impFail, genFail, cond) {
  if (impFail === 0 && genFail === 0) return 'QUALIFIED'
  if (impFail >= 1) return 'DISQUALIFIED'
  if (genFail > 8) return 'DISQUALIFIED'
  const conditional = cond && cond.siteLimited === true && cond.tripartiteAgreed === true && cond.controlMeasures
  if (genFail >= 1 && genFail <= 3 && conditional) return 'CONDITIONAL'
  return 'RECTIFY'
}
// C-12：一梯一档 7 条归档目录（按移交单 id，SHA-256 占位，与后端 archive() 同结构）
function handoverArchiveEntries(id) {
  const cats = [['01', 'CHECKLIST'], ['02', 'RECTIFICATION'], ['03', 'DOCS'], ['04', 'PHOTOS'],
    ['05', 'CONFIRMATION'], ['06', 'FIRST_ORDER'], ['07', 'PENDING_DOCS']]
  return cats.map((c, i) => ({ id: 'ha_' + id + '_' + c[0], handoverId: id,
    category: c[1], seq: i + 1, sha256: 'mock_sha256_' + c[0], archivedAt: formatTime(new Date()) }))
}

// 演示移交单（屏 02/24 原型基线 YJ-2026-0117）：模块加载即存在，避免依赖会话内 POST 持久化。
// 数据为合成演示件，结构与后端 HandoverOrder/HandoverCheckItem 同口径（docs/04 A.9 / docs/15）。
function seedDemoHandovers() {
  const docs = [
    { docType: 'FACTORY_CERT', docName: '出厂合格证', fileId: 'mock_file_1', missing: false },
    { docType: 'TYPE_TEST', docName: '型式试验报告', fileId: '', missing: true },
    { docType: 'INSTALL_NOTICE', docName: '安装告知书', fileId: 'mock_file_3', missing: false },
    { docType: 'SELF_CHECK', docName: '自检报告', fileId: 'mock_file_4', missing: false },
    { docType: 'HIDDEN_WORK', docName: '隐蔽工程记录', fileId: 'mock_file_5', missing: false },
    { docType: 'AS_BUILT', docName: '竣工图纸', fileId: 'mock_file_6', missing: false }
  ]
  const items = buildHandoverChecklist()
  items.forEach(function (it) {
    if (it.itemNo === 38) { it.result = 'FAIL'; it.failDesc = '张紧轮松动，需整改后复验'; it.photoFileIds = 'mock_file_38' }
    else if (it.itemNo >= 57) { it.result = 'PASS'; it.measuredValue = '0.52' }
    else { it.result = 'PASS' }
  })
  const base = {
    elevatorId: 'el_2', elevatorCode: 'EM-2024-002', elevatorName: '世纪大厦 2# 客梯', projectName: '世纪大厦',
    location: '渝北区龙山一路 88 号', category: '曳引与强制驱动电梯', deviceCode: 'DT-CB-0231', useUnit: '启元物业',
    installUnit: '中建安装', maintUnit: '博威电梯', initiatorName: '王强 · 中建安装', uploaderName: '刘倩 · 安装部资料员',
    ratedLoad: 1050, ratedLoadUnit: 'kg', ratedSpeed: 1.75, ratedSpeedUnit: 'm/s', stationsDoors: '18 / 18',
    workTypeCode: 'HM', supervisionPassDate: '2026-11-01', receiverId: 'emp_lzg', receiverName: '李振国',
    supervisionReportFileId: 'mock_file_a', maintenanceManualFileId: 'mock_file_b', qualityCertificateFileId: 'mock_file_c',
    docs: docs, keys: ['层门钥匙 ×6', '机房钥匙 ×2', '检修盒 ×1', '盘车手轮'],
    checklist74: items, checkConclusion: 'RECTIFY', checkCounts: { pass: 73, fail: 1, na: 0, impFail: 0 },
    rectifications: [{ id: 'hr_demo_1', handoverId: 'ho_demo_1', itemNo: 38, itemType: 'G05', respParty: '安装单位',
      source: 'INSPECT', voided: false, beforePhotoFileId: 'mock_file_38', recheckResult: null,
      failDesc: '张紧轮松动，需整改后复验' }]
  }
  mockHandovers.push(Object.assign({}, base, { id: 'ho_demo_1', handoverNo: 'YJ-2026-0117', status: 'INSPECTING',
    createdAt: '2026-11-12 16:20:00', checks: [], signs: [] }))
  mockHandovers.push(Object.assign({}, base, { id: 'ho_demo_2', handoverNo: 'YJ-2026-0118', status: 'RECTIFYING',
    createdAt: '2026-11-12 16:20:00', checks: [], signs: [],
    rectifications: [{ id: 'hr_demo_2', handoverId: 'ho_demo_2', itemNo: 38, itemType: 'G05', respParty: '安装单位',
      source: 'INSPECT', voided: false, beforePhotoFileId: 'mock_file_38', recheckResult: null,
      failDesc: '张紧轮松动，需整改后复验' }] }))
}
seedDemoHandovers()

// M1 统一登录响应扩展（docs/04 V2.53）：账号登录/微信免密登录共用，与后端 loginResult 口径一致
function loginPayload(roleKey) {
  const user = d.db.employees[roleKey]
  // 旧码归一走根别名表（constants/permission-matrix.ALIAS，与后端 RoleCatalog.ALIAS 同源）——
  // 此前此处手写第三份别名表，与 ALIAS 重复维护易漂移（P1 T7 裁决：统一引用）
  const canon = permMirror.canonical(roleKey) || roleKey
  const roles = (user.roles && user.roles.length ? user.roles : [canon]).map((r) => permMirror.canonical(r) || r)
  currentRoles = roles
  const perms = permsOf(roles)
  const ws = menus.activeWorkspace(roles)
  const scope = roles.indexOf('PLATFORM_ADMIN') > -1 ? '全部数据'
    : isCustomerPrincipal(roles) ? '本单位全部电梯'
      : roles.some((r) => r.indexOf('INST') === 0) ? '安装部项目范围' : '本班组维保范围'
  // 与后端 AuthController.userInfo() 同口径：会话体不含 password（2026-11-16 审查修复）
  const userInfo = Object.assign({}, user)
  delete userInfo.password
  return {
    token: 'mock-token-' + Date.now(), userInfo, role: roleKey,
    roles, perms, scope, activeWorkspace: ws, home: menus.resolveHome(roles),
    // P1 T7：主体类型（后端 RoleCatalog.isCustomerPrincipal 口径），T8 小程序据此收敛客户入口
    principalType: principalTypeOf(roles)
  }
}

// 统一登录客户分支（client_account，与后端 ClientLoginService 同形状）：
// 串单位直接签发；多单位返回 needSelectUnit + 候选（口令已验证才调用）。
function clientLoginPayload(matched) {
  if (matched.length === 1) {
    const a = matched[0]
    const u = d.db.useUnits.find((x) => x.id === a.useUnitId)
    const unitName = u ? u.unitName : ''
    currentRole = a.role
    currentRoles = [a.role]
    currentClient = a
    return {
      token: 'mock-client-token-' + a.id,
      principalType: 'CUSTOMER',
      role: a.role,
      roles: [a.role],
      useUnitId: a.useUnitId,
      needSelectUnit: false,
      perms: ['client:elevator_view', 'client:sign', 'client:download', 'client:report'],
      home: '/pages/client/home/index',
      activeWorkspace: 'CLIENT',
      scope: (unitName ? unitName : '本单位') + '·本单位',
      userInfo: { id: a.id, name: a.name, phone: a.phone, role: a.role, unitName, useUnitId: a.useUnitId }
    }
  }
  return {
    principalType: 'CUSTOMER',
    needSelectUnit: true,
    selectionToken: 'mock-sel-' + matched[0].phone,
    units: matched.map((a) => {
      const u = d.db.useUnits.find((x) => x.id === a.useUnitId)
      return { useUnitId: a.useUnitId, unitName: u ? u.unitName : '', accountId: a.id, role: a.role }
    })
  }
}
const activeClientAccounts = () => (d.db.clientAccounts || []).filter((a) => a.status !== 'DISABLED' && a.enabled !== 0)

// 电梯档案概览（屏 08）：规格卡/服务履历/统计/联系，全部由 db 推导，不硬编码
function buildElevatorOverview(el) {
  const unit = d.getUseUnit(el.useUnitId) || {}
  const orders = (d.db.orders || []).filter((o) => o.elevatorId === el.id)
  const faults = (d.db.faults || []).filter((f) => f.elevatorCode === el.elevatorCode)
  const log = []
  orders.slice(0, 4).forEach((o) => log.push({ tone: o.status === 'DONE' ? 'ok' : 'amber', title: (o.workType || '维保') + ' · ' + (o.orderNo || ''), sub: [o.workerName, o.status === 'DONE' ? '已签退归档' : '进行中'].filter(Boolean).join(' · '), date: (o.checkoutTime || o.planTime || '').slice(0, 10) }))
  faults.slice(0, 2).forEach((f) => log.push({ tone: f.rescue ? 'signal' : 'amber', title: (f.rescue ? '急修 · 困人救援' : '急修') , sub: [f.id, f.workerName, f.status === 'CLOSED' ? '已闭环' : '处理中'].filter(Boolean).join(' · '), date: (f.createdAt || '').slice(0, 10) }))
  const done = orders.filter((o) => o.status === 'DONE').length
  const resp = faults.filter((f) => f.responseMinutes != null)
  const avgResp = resp.length ? Math.round(resp.reduce((s, f) => s + f.responseMinutes, 0) / resp.length) : 0
  const nextCheck = el.nextCheckDate || ''
  const daysToCheck = nextCheck ? Math.max(0, Math.ceil((new Date(nextCheck).getTime() - Date.now()) / 86400000)) : 0
  return {
    useUnitName: unit.name || el.useUnitName || '',
    contractNo: unit.contractNo || '', contractPeriod: unit.contractPeriod || '',
    nextService: el.nextMaintenanceDate || '', inspection: nextCheck,
    serviceLog: log,
    stats: { fault30: faults.length, avgResp, daysToCheck },
    contact: { unit: unit.unitPrincipal || '', unitPhone: unit.unitPrincipalPhone || '', main: el.maintPrincipal || '', mainPhone: el.maintPrincipalPhone || '', assist: el.assistPhone || '', addr: el.location || '', contractNo: unit.contractNo || '' }
  }
}
const routes = [
  // ── M6 管理统计域只读投影（pkg-admin，复用管理端端点） ──
  ['GET', '/projects', () => ({ list: (d.db.projects || []).slice(0, 50), total: (d.db.projects || []).length })],
  ['GET', '/contracts', () => ({ list: (d.db.contracts || []).slice(0, 50), total: (d.db.contracts || []).length })],
  ['GET', '/users', () => ({ list: Object.keys(d.db.employees).map((k) => { const e = d.db.employees[k]; return { id: e.id, name: e.name, account: e.account, role: e.role, roleText: e.roleText, groupName: e.groupName, enabled: e.enabled !== false } }), total: Object.keys(d.db.employees).length })],
  // 2026-11-16 审查修复：补齐 services 已调用但 mock 缺失的 4 端点（docs/04 既有契约，三处同步）
  // GET /employees：后端 AdminArchiveController 回纯数组；接收工程师候选按旧码 WORKER/ASSISTANT 过滤
  ['GET', '/employees', () => Object.keys(d.db.employees).map((k) => { const e = d.db.employees[k]; return { id: e.id, name: e.name, role: e.role === 'MAINT_TECH' ? 'WORKER' : e.role, groupName: e.groupName, enabled: e.enabled !== false } })],
  ['GET', '/use-units', () => d.db.useUnits],
  // 使用单位档案写路由（docs/04 A 部分 / docs/09 §4.1；三处同步）。手机号互斥 1002 对齐后端 checkUseUnit：负责人手机 ≠ 安全管理员手机。
  ['POST', '/use-units', ({ body }) => {
    if (!body.unitName || !String(body.unitName).trim()) throw { code: 422, message: '使用单位名称必填' }
    if (body.unitPrincipalPhone && body.unitPrincipalPhone === body.elevatorAdministerPhone) {
      throw { code: 1002, message: '手机号互斥', data: { conflicts: [{ field: 'elevatorAdministerPhone', owner: body.elevatorAdminister || '安全管理员', target: '使用单位负责人' }] } }
    }
    const u = Object.assign({ id: 'uu_' + Date.now() }, body)
    d.db.useUnits.push(u)
    return u
  }],
  ['PUT', '/use-units/:id', ({ params, body }) => {
    const u = d.db.useUnits.find((x) => x.id === params.id)
    if (!u) throw { code: 1404, message: '使用单位不存在' }
    if (body.unitPrincipalPhone && body.unitPrincipalPhone === body.elevatorAdministerPhone) {
      throw { code: 1002, message: '手机号互斥', data: { conflicts: [{ field: 'elevatorAdministerPhone', owner: body.elevatorAdminister || '安全管理员', target: '使用单位负责人' }] } }
    }
    Object.assign(u, body)
    return u
  }],
  ['DELETE', '/use-units/:id', ({ params }) => {
    const idx = d.db.useUnits.findIndex((x) => x.id === params.id)
    if (idx < 0) throw { code: 1404, message: '使用单位不存在' }
    const linked = (d.db.elevators || []).filter((e) => e.useUnitId === params.id).length
    if (linked) throw { code: 422, message: '该单位名下有 ' + linked + ' 台电梯，请先移除关联后再删除' }
    d.db.useUnits.splice(idx, 1)
    return { ok: true }
  }],

  // ── P3 客户账号（docs/14 §6；三处同步 mock/server.js + services/admin api + docs/04）──
  ['GET', '/use-units/:id/accounts', ({ params }) => (d.db.clientAccounts || []).filter((a) => a.useUnitId === params.id)],
  ['POST', '/use-units/:id/accounts', ({ params, body }) => {
    const unit = d.db.useUnits.find((u) => u.id === params.id)
    if (!unit) throw { code: 1404, message: '使用单位档案不存在' }
    if (!body.name || !String(body.name).trim()) throw { code: 422, message: '客户联系人姓名必填' }
    const phone = String(body.phone || '').replace(/\s/g, '')
    if (!/^1\d{10}$/.test(phone)) throw { code: 422, message: '手机号格式不正确' }
    const role = body.role || 'CLIENT_ADMIN'
    if (!['CLIENT_ADMIN', 'CLIENT_SIGNER'].includes(role)) throw { code: 422, message: '客户角色仅支持 CLIENT_ADMIN/CLIENT_SIGNER' }
    const dup = (d.db.clientAccounts || []).find((a) => a.useUnitId === params.id && a.phone === phone && a.status !== 'DISABLED')
    if (dup) throw { code: 1002, message: '该手机号在本单位已开通客户账号' }
    const emp = Object.values(d.db.employees).find((e) => e.phone === phone)
    if (emp) throw { code: 1002, message: '该手机号已绑定内部员工账号' }
    const rawPwd = body.password || phone.slice(-6)
    if (String(rawPwd).length < 6) throw { code: 422, message: '初始口令至少 6 位' }
    const row = {
      id: 'ca_' + Date.now(), useUnitId: params.id, name: body.name.trim(), phone,
      password: rawPwd, role, roleText: role === 'CLIENT_ADMIN' ? '使用单位管理员' : '使用单位签字人',
      principalType: 'CUSTOMER', status: 'ACTIVE', enabled: 1, graceUntil: '', wechatBound: false,
      createdBy: '', createdAt: formatTime(new Date()), lastLoginAt: '', disabledReason: ''
    }
    d.db.clientAccounts.push(row)
    return Object.assign({}, row, { initialPassword: rawPwd })
  }],
  ['PUT', '/use-units/:unitId/accounts/:accountId', ({ params, body }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    if (body.name) a.name = body.name
    if (body.phone && /^1\d{10}$/.test(body.phone)) a.phone = body.phone
    if (body.role) { a.role = body.role; a.roleText = body.role === 'CLIENT_ADMIN' ? '使用单位管理员' : '使用单位签字人' }
    return a
  }],
  ['PATCH', '/use-units/:unitId/accounts/:accountId/disable', ({ params, body }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    a.status = 'DISABLED'; a.enabled = 0; a.disabledReason = 'MANUAL_' + (body && body.reason ? body.reason : 'OPERATOR'); a.graceUntil = ''
    return a
  }],
  ['PATCH', '/use-units/:unitId/accounts/:accountId/enable', ({ params }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    a.status = 'ACTIVE'; a.enabled = 1; a.disabledReason = ''; a.graceUntil = ''
    return a
  }],
  ['POST', '/use-units/:unitId/accounts/:accountId/reset-password', ({ params, body }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    const pwd = (body && body.password) || a.phone.slice(-6)
    if (String(pwd).length < 6) throw { code: 422, message: '新口令至少 6 位' }
    a.password = pwd
    return Object.assign({}, a, { password: pwd })
  }],
  ['POST', '/use-units/:unitId/accounts/:accountId/bind-wechat', ({ params }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    a.wechatBound = true
    return a
  }],
  ['POST', '/use-units/:unitId/accounts/:accountId/restore-grace', ({ params, body }) => {
    const a = (d.db.clientAccounts || []).find((x) => x.id === params.accountId && x.useUnitId === params.unitId)
    if (!a) throw { code: 1404, message: '客户账号不存在' }
    if (a.status !== 'DISABLED') throw { code: 422, message: '仅停用账号可临时恢复宽限' }
    a.status = 'GRACE'; a.enabled = 1; a.graceUntil = (body && body.graceUntil) || ''; a.disabledReason = ''
    return a
  }],
  // 客户选单位（登录已并入 /auth/login，多单位时凭 selectionToken 到这里换正式令牌）
  ['POST', '/auth/client/select-unit', ({ body }) => {
    const a = activeClientAccounts().find((x) => x.useUnitId === body.useUnitId)
    if (!a) throw { code: 1403, message: '该单位下无此客户账号' }
    return clientLoginPayload([a])
  }],
  ['GET', '/auth/client/me', () => {
    const a = currentClient
    return a ? { id: a.id, name: a.name, phone: a.phone, role: a.role, useUnitId: a.useUnitId, principalType: 'CUSTOMER' } : { principalType: 'CUSTOMER' }
  }],
  ['POST', '/handovers/:id/assign-receiver', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    Object.assign(o, { receiverId: body.receiverId || '', receiverName: body.receiverName || '' })
    return o
  }],
  ['PUT', '/faults/:id', ({ params, body }) => {
    const f = findOr404(d.db.faults, params.id, '急修单')
    // 与后端 DirectoryService.updateFault 同口径：仅维修过程四字段
    ;['siteDesc', 'todoDesc', 'handleDesc', 'finishedAt'].forEach((k) => { if (body[k] != null) f[k] = body[k] })
    return f
  }],
  ['GET', '/roles-matrix', () => ({ roles: menus.ROLE_PRIORITY, matrix: {} })],
  ['GET', '/stats', () => ({ managed: d.db.elevators.length, done: d.db.orders.filter((o) => o.status === 'DONE').length, openFaults: d.db.faults.filter((f) => f.status === 'OPEN').length, pendingSigns: d.db.unitRecords.filter((r) => r.confirmStatus === 'PENDING').length })],
  ['GET', '/audit', () => ({ list: [], total: 0 })],
  ['GET', '/settings', () => ([{ key: 'record_review_enabled', value: '0', remark: '维保记录复核开关' }, { key: 'sla_minutes', value: '30', remark: '急修 SLA 分钟' }])],
  ['PUT', '/settings/:key', ({ params, body }) => ({ ok: true, key: params.key, value: body.value })],
  // ── M1 小程序重构后端底座（docs/04 V2.53 三处同步） ──
  ['GET', '/me/menus', ({ query }) => {
    // M2：与后端 MiniappMenuService 同投影（require 前端镜像，漂移守卫锁定 ROLE_TABS）
    const roles = currentRoles.length ? currentRoles : ['MAINT_TECH']
    const effective = query.workspace && roles.indexOf(query.workspace) > -1 ? [query.workspace] : roles
    const ws = menus.activeWorkspace(effective)
    const actions = {}
    const BUTTON_CODES = require('../services/menu').BUTTON_CODES
    BUTTON_CODES.forEach((c) => { actions[c] = effective.some((r) => permMirror.allowsNew(r, c)) })
    // 2026-11-16 审查修复：与后端 MiniappMenuService.quickActions 同口径（角色感知，权限面=渲染面）
    const has = (r) => effective.indexOf(r) > -1
    let quickActions = []
    if (ws && ws.indexOf('MAINT') === 0) {
      quickActions = [
        { key: 'emergency', text: '急修单', icon: 'bolt', path: '/pages/order/list/index?state=urgent' },
        { key: 'report', text: '报修接单', icon: 'wrench', path: '/pages/order/list/index?state=pending' },
        { key: 'handover', text: '移交验收', icon: 'file-signature', path: '/pkg-handover/pages/list/index' }
      ]
      if (has('MAINT_SUP') || has('MAINT_MGR')) quickActions.push({ key: 'plan', text: '维保计划', icon: 'calendar-check-r', path: '/pkg-maintenance/pages/plan/index' })
      if (has('MAINT_SUP') || has('MAINT_MGR') || has('MAINT_BIZ') || has('PLATFORM_ADMIN')) quickActions.push({ key: 'filing', text: '外接建档', icon: 'folder', path: '/pkg-handover/pages/filing-init/index' })
    } else if (ws && ws.indexOf('CLIENT') === 0) {
      // 2026-11-21 裁决：与后端同口径——客户一键报修重开（client:report CA/CS 双 Y），
      // 急修进度/评价仍下线；报修项按会话 perms 门控（与 services/menu.js clientQuickActions 同口径）
      quickActions = []
      if (permsOf(effective).indexOf('client:report') > -1) {
        quickActions.push({ key: 'report', text: '一键报修', icon: 'wrench', path: '/pages/order/report/index' })
      }
      quickActions.push({ key: 'elevators', text: '我的电梯', icon: 'elevator', path: '/pages/elevator/list/index' })
      quickActions.push({ key: 'todo', text: '待办签字', icon: 'file-signature', path: '/pkg-client/pages/todo/index' })
    } else if (ws && ws.indexOf('INST') === 0) {
      // 2026-11-21 裁决：handover:create 增 INST_CLERK，快捷动作同步放开
      if (has('INST_CLERK') || has('INST_PM')) quickActions.push({ key: 'create', text: '发起移交', icon: 'file-signature', path: '/pkg-handover/pages/create/index' })
      if (has('INST_CLERK') || has('INST_PM')) quickActions.push({ key: 'upload', text: '资料上传', icon: 'folder', path: '/pkg-handover/pages/upload/index' })
    }
    const badges = { pendingOrders: 0, pendingSigns: 0 }
    // 「工单」角标=数据域内未闭环工单（PENDING/PROCESSING，与工单列表同 orderVisible 谓词）；
    // 旧实现错挂 fault OPEN 计数（后端 MiniappMenuService.badges 2026-11-21 同修）
    if (effective.some((r) => r.indexOf('MAINT') === 0)) {
      badges.pendingOrders = d.listOrders({}).filter(orderVisible).filter((o) => o.status !== 'DONE').length
    }
    // P1 T5/T7：客户待签角标按本单位 + 可签集合收敛（后端 MiniappMenuService.clientPendingSigns 同口径），
    // 无锚点 fail-closed 计 0；主体判定用 principalType（含旧码 UNIT_ADMIN），不用字面量前缀
    if (effective.some((r) => isCustomerPrincipal([r]))) badges.pendingSigns = clientPendingSignCount()
    return {
      user: { roles: effective, activeWorkspace: ws },
      home: menus.resolveHome(effective),
      activeWorkspace: ws,
      tabs: menus.resolveTabs(effective),
      quickActions, actions, badges
    }
  }],
  ['POST', '/work-orders/:id/signature-refuse', () => ({ ok: true })],
  ['POST', '/work-orders/:id/review', ({ body }) => ({ ok: true, lifecycleStatus: body.pass ? 'PENDING_CLIENT_SIGN' : '' })],
  // 发起使用单位签字（后端 RecordLifecycleService.signRequest）：角色门禁（主检/维保主管）→ 定位记录 →
  // 推进生命周期后客户才可见/可签。后端按记录 id 定位，小程序历史上也会传工单 id，两者都兜住；
  // 双签齐门控以后端为准（mock 不拦）。
  ['POST', '/work-orders/:id/sign-request', ({ params }) => {
    const canon = currentRoles.map((r) => permMirror.canonical(r))
    if (canon.indexOf('MAINT_TECH') < 0 && canon.indexOf('MAINT_SUP') < 0) {
      throw { code: 1403, message: '无发起签署权限' }
    }
    const o = (d.db.orders || []).find((x) => x.id === params.id)
    const rec = (d.db.unitRecords || []).find((r) => r.id === params.id) ||
      (o ? (d.db.unitRecords || []).find((r) => r.originalRecordId && r.originalRecordId === o.originalRecordId) : null)
    if (!rec) throw { code: 1404, message: '维保记录不存在' }
    if (rec.lifecycleStatus !== 'PENDING_CLIENT_SIGN') throw { code: 422, message: '记录不在待使用单位签字状态' }
    rec.lifecycleStatus = 'PENDING_CLIENT_SIGNATURE'
    // V2.80：签字 SLA 起算打点（重新发起时重置提醒去重标记）
    rec.signRequestedAt = formatTime()
    rec.signRemindedAt = null
    return { ok: true, shareToken: rec.shareToken || '' }
  }],
  ['GET', '/work-orders/:id/archive', () => ({ url: 'https://mock/files/arch?s=mock', hash: '0'.repeat(64), archivedAt: '2026-11-15 00:00:00' })],
  // 使用单位拒签（后端 RecordLifecycleService.refuseByToken，T4-I4）：token 401 先于状态披露 →
  // 唯一可签状态 → confirmStatus=PENDING → 链末端最新版本 → 原因必填，任一失败 422 且无副作用；
  // 成功走条件更新等价判定（并发先签为准），退回 REFUSED_RETURNED；不触发平台 2.6
  ['POST', '/unit/records/:id/refuse-by-token', ({ params, query, body }) => {
    const r = findOr401Token(d.db.unitRecords, params.id) // C-25⑧：不存在 → 401 同错 token 文案
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    if (r.lifecycleStatus !== 'PENDING_CLIENT_SIGNATURE') throw { code: 422, message: '记录不在待使用单位签署状态' }
    if (r.confirmStatus !== 'PENDING') throw { code: 422, message: '记录已签署，不能拒签' }
    if (!isLatestVersion(r)) throw { code: 422, message: '记录已被新版本替代，请处理最新记录' }
    // V2.80 分类码：传码校验 R1—R5（R5 必填说明）；未传码按兼容期回退「原因必填」
    const code = String((body && body.refuseCode) || '').trim().toUpperCase()
    const reason = String((body && body.reason) || '').trim()
    if (!code) {
      if (!reason) throw { code: 422, message: '拒绝原因必填' }
    } else {
      if (!REFUSE_CODES[code]) throw { code: 422, message: '拒签原因分类码无效（R1—R5）' }
      if (code === 'R5' && !reason) throw { code: 422, message: '选择「其他」时必须填写拒签说明' }
    }
    r.lifecycleStatus = 'REFUSED_RETURNED'
    r.refuseReason = code ? (reason ? REFUSE_CODES[code] + '：' + reason : REFUSE_CODES[code]) : reason // 内部字段：客户白名单一律不回显（docs/14 §4.7 DC5）
    r.refuseCode = code || null
    return { ok: true }
  }],
  ['POST', '/faults/:id/grab', () => ({ ok: true, status: 'ASSIGNED' })],
  ['POST', '/faults/:id/arrive', () => ({ ok: true, status: 'PROCESSING' })],
  ['POST', '/faults/:id/submit', () => ({ ok: true, status: 'PENDING_CONFIRM' })],
  ['POST', '/faults/:id/confirm', ({ body }) => ({ ok: true, status: body.pass === false ? 'REFUSED' : 'CLOSED' })],
  ['POST', '/faults/:id/cancel', () => ({ ok: true, status: 'CANCELLED' })],
  ['POST', '/faults/:id/evaluate', () => ({ ok: true })],
  // ── 鉴权 ──
  // 账号密码登录（用户需求：账号由维保单位系统分配，手机号为账号）
  // 统一登录（唯一入口，与后端 AuthController.login 同口径）：员工目录命中即员工；
  // 否则按 client_account 校验客户账号（多单位返回候选），前端不区分通道。
  ['POST', '/auth/login', ({ body }) => {
    const phone = String(body.phone || '').trim()
    const password = String(body.password || '')
    let roleKey = null
    let user = null
    Object.keys(d.db.employees).forEach(function (k) {
      if (d.db.employees[k].account === phone) { roleKey = k; user = d.db.employees[k] }
    })
    if (user) {
      if (user.password !== password) throw { code: 401, message: '账号或密码错误' }
      currentClient = null
      currentRole = roleKey
      return loginPayload(roleKey)
    }
    const all = (d.db.clientAccounts || []).filter((a) => a.phone === phone)
    const matched = activeClientAccounts().filter((a) => a.phone === phone && a.password === password)
    if (all.length && !activeClientAccounts().some((a) => a.phone === phone)) {
      throw { code: 403, message: '该客户账号已停用，请联系使用单位管理员' }
    }
    if (!matched.length) throw { code: 401, message: '账号或密码错误' }
    return clientLoginPayload(matched)
  }],
  // 自助改密（docs/04 A.0.1；与后端 AuthController 同口径：校验原密码、新密码 ≥6 位且不同）
  ['POST', '/auth/change-password', ({ body }) => {
    const user = d.db.employees[currentRole] || d.db.employees.WORKER
    const oldPassword = String((body && body.oldPassword) || '')
    const newPassword = String((body && body.newPassword) || '')
    if (!user || user.password !== oldPassword) throw { code: 401, message: '原密码不正确' }
    if (newPassword.length < 6) throw { code: 422, message: '新密码至少 6 位' }
    if (newPassword === oldPassword) throw { code: 422, message: '新密码不能与原密码相同' }
    user.password = newPassword
    return { ok: true }
  }],
  // 登录后绑定微信：wx.login code → 服务端换 openid 并与账号关联（mock 直接返回成功）
  ['POST', '/auth/bind-wechat', ({ body }) => {
    if (!body.code) throw { code: 422, message: '缺少微信 code' }
    return { ok: true } // 后端只回 {ok:true}，openid 不出服务端（AGENTS §2.6）
  }],
  ['POST', '/auth/wx-login', ({ body }) => {
    const role = d.db.employees[body.role] ? body.role : 'WORKER'
    currentClient = null
    currentRole = role
    return loginPayload(role)
  }],
  // 忘记密码·微信验证重置（docs/04 V2.55 三处同步；mock 以演示账号模拟已绑定微信）
  ['POST', '/auth/reset-password-by-wechat', ({ body }) => {
    if (!body.code) throw { code: 422, message: '缺少微信 code' }
    const newPassword = String(body.newPassword || '')
    if (newPassword.length < 6) throw { code: 422, message: '新密码至少 6 位' }
    const role = d.db.employees[body.role] ? body.role : 'WORKER'
    const user = d.db.employees[role]
    user.password = newPassword
    return { ok: true, account: user.account }
  }],
  ['POST', '/auth/logout', () => ({ ok: true })],

  // ── 工单 ──
  ['GET', '/home/summary', () => d.getHomeSummary()],
  // 使用单位账号电梯收敛本单位（2026-11-15 裁决；与后端 CLIENT 谓词同口径，无锚点 fail-closed）
  ['GET', '/elevators', () => {
    const anchor = clientUnitAnchor()
    const list = d.listElevators()
    if (anchor === undefined) return list
    if (anchor === null) return []
    const ids = new Set(d.db.elevators.filter((el) => el.useUnitId === anchor).map((el) => el.id))
    return list.filter((row) => ids.has(row.id))
  }],
  // 扫码回填电梯信息（急修单用，docs/04 A.0.1）
  ['GET', '/elevators/by-code', ({ query }) => {
    const el = d.getElevatorByCode(String((query && query.code) || '').trim())
    if (!el) throw { code: 1404, message: '电梯不存在，请核对编号' }
    if (el.status === 'INACTIVE') throw { code: 422, message: '该电梯已停用，贴梯二维码失效（docs/09 §6.6）' }
    return {
      id: el.id,
      elevatorCode: el.elevatorCode,
      elevatorName: el.elevatorName,
      location: el.location,
      regCode: el.regCode,
      deviceCode: el.deviceCode,
      model: el.model,
      category: el.category
    }
  }],
  // 工单列表（V7 班组谓词 + 区域池谓词 orderVisible，后端 listOrders/scopeOrders 同口径；
  // 池化工单行 workerName/workerPlatformId 为空，regionId 为派单时电梯区域快照）
  ['GET', '/work-orders', ({ query }) => paginate(d.listOrders(query).filter(orderVisible), query)],
  ['GET', '/work-orders/:id', ({ params }) => {
    const order = d.getOrder(params.id)
    if (!order) throw { code: 1404, message: '工单不存在' }
    return order
  }],
  ['GET', '/elevators/:id/profile', ({ params }) => {
    const p = d.getElevatorProfile(params.id)
    if (!p) throw { code: 1404, message: '电梯不存在' }
    const anchor = clientUnitAnchor()
    const el = d.getElevator(params.id)
    if (anchor !== undefined && el && el.useUnitId !== anchor) throw { code: 1403, message: '无权查看其他使用单位电梯' }
    p.overview = buildElevatorOverview(el)
    return p
  }],
  // 维保人员现场更正项目名称（小区名称，管理端可能写错；docs/04 A.2.1）
  ['PUT', '/elevators/:id/project-name', ({ params, body }) => {
    const el = d.getElevator(params.id)
    if (!el) throw { code: 1404, message: '电梯不存在' }
    const v = String((body && body.projectName) || '').trim()
    if (!v) throw { code: 422, message: '项目名称不能为空' }
    el.projectName = v
    el.elevatorName = v // 电梯名称 = 项目名称（小区），合并
    return { ok: true }
  }],
  // 维保人员现场维护设备本地信息（docs/04 A.2.1；不开放经纬度/使用单位负责人）
  ['PUT', '/elevators/:id/local', ({ params, body }) => {
    const el = d.getElevator(params.id)
    if (!el) throw { code: 1404, message: '电梯不存在' }
    const fields = ['brand', 'manufacturer', 'factoryNumber', 'driveMode',
      'ratedLoad', 'ratedLoadUnit', 'ratedSpeed', 'ratedSpeedUnit', 'stationsDoors', 'nextCheckDate', 'nextMaintenanceDate']
    let any = false
    fields.forEach(function (f) {
      if (body && body[f] !== undefined) {
        el[f] = body[f]
        any = true
      }
    })
    // 安装地址：profile 返回 local.address，落库字段为 location
    if (body && body.address !== undefined) {
      el.location = String(body.address || '')
      any = true
    }
    // 项目名称：空校验 + 同步电梯名称（电梯名称 = 项目名称，合并）
    if (body && body.projectName !== undefined) {
      const v = String(body.projectName || '').trim()
      if (!v) throw { code: 422, message: '项目名称不能为空' }
      el.projectName = v
      el.elevatorName = v
      any = true
    }
    if (!any) throw { code: 422, message: '无可更新的设备信息' }
    return { ok: true }
  }],
  // Minor7：只读查询改 GET（与后端 2026-10-05 整改一致）
  ['GET', '/work-orders/resolve-by-elevator', ({ query }) => {
    const el = d.getElevatorByCode(query.elevatorCode)
    if (!el) throw { code: 1404, message: '未识别的电梯二维码' }
    const order = d.db.orders.find((o) => o.elevatorId === el.id && o.status !== 'DONE')
    if (!order) throw { code: 1404, message: '该电梯暂无进行中的工单' }
    return d.getOrder(order.id)
  }],
  ['POST', '/work-orders/:id/checkin', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status === 'DONE') throw { code: 1003, message: '当前状态不允许签到' }
    const assistant = body.role === 'ASSISTANT'
    const me = currentEmployee()
    // 区域抢单池「签到即认领」（M1/D4，后端 claimOrderAsPrincipal 同口径）：
    // 主维保签到已被他人认领的工单 → 1003 并回显认领人（原子条件更新命中 0 行的等价语义）
    if (!assistant && o.workerPlatformId && me.platformId && o.workerPlatformId !== me.platformId) {
      throw { code: 1003, message: '该工单已被 ' + (o.workerName || '其他维保人员') + ' 认领' }
    }
    // 主维保与配合人员分别签到，各自一条留痕（docs/01 §3.7.2）
    if (assistant ? o.assistantCheckinExtra : o.checkinExtra) {
      throw { code: 1003, message: (assistant ? '配合人员' : '主维保人员') + '已签到，不可重复签到' }
    }
    const lat = Number(body.latitude)
    const lng = Number(body.longitude)
    if (!isFinite(lat) || !isFinite(lng)) {
      throw { code: 422, message: '签到必须携带定位坐标；定位失败请重试（严禁伪造坐标）' }
    }
    // 配合人门禁（后端 WorkOrderService.requireAssignedAssistant 同口径，先于动态码消费）：
    // ①M5 身份比对——已指定配合人时仅本人可签，错误人选 1003 不吞码；
    // ②I2 fail-closed——池化新单（regionId 非空或 checkinExtra.claimedViaPool）未指定配合人一律 1003；
    // 存量工单（regionId 空且无打标）维持 M8 原动态码口径，不做身份比对
    if (assistant) {
      if (o.assistantPlatformId) {
        if (!me.platformId || me.platformId !== o.assistantPlatformId) {
          throw { code: 1003, message: '你未被指定为配合人（本工单配合人已由主维保指定）' }
        }
      } else if (o.regionId || (o.checkinExtra && o.checkinExtra.claimedViaPool)) {
        throw { code: 1003, message: '主维保尚未指定配合人，请由主维保指定后再签到' }
      }
    }
    // 配合人员须携带主维保生成、未过期且未使用的动态码（一次性消费）
    if (assistant) d.consumeDynamicCode(o, body.dynamicCode)
    // 认领写库在任何校验通过之后、签到留痕之前（与后端顺序一致：校验失败零副作用）；
    // 无平台ID 不得认领（D4：2.6 workMan1Id/workMan2Id 必填的源头门禁）
    let claimedNow = false
    if (!assistant && !o.workerPlatformId) {
      if (!me.platformId) throw { code: 422, message: '请先在管理端完成平台人员登记' }
      o.workerName = me.name
      o.workerPlatformId = me.platformId
      claimedNow = true
    }
    // 定位校验：与后端同口径计算距离；mock 只回显不拦截，拦截口径以后端为准（docs/04 A.0.1 说明）
    const el = d.getElevator(o.elevatorId)
    const threshold = (el && el.checkinThreshold) || 200
    const raw = (el && el.lat && el.lng) ? distanceMeters(lat, lng, el.lat, el.lng) : -1
    const distance = raw < 0 ? -1 : Math.round(raw * 10) / 10
    const geoStatus = distance < 0 ? 'UNKNOWN' : 'PROVIDED'
    d.markCheckin(o.id, body, { distance: distance, threshold: threshold, geoStatus: geoStatus })
    // D8 池化认领打标（后端 checkinExtraJson.claimedViaPool 同口径）：签退恒双人门禁据此覆盖不限区域池单
    if (claimedNow && o.checkinExtra) o.checkinExtra.claimedViaPool = true
    return {
      checkinId: 'chk_' + Date.now(),
      role: assistant ? 'ASSISTANT' : 'PRINCIPAL',
      distance: distance < 0 ? null : distance,
      threshold: distance < 0 ? null : threshold,
      passed: true,
      geoStatus: geoStatus,
      geoBackfilled: false
    }
  }],
  // 主维保生成双人动态码（每 5 秒刷新、60 秒有效，docs/03 §3.2 项6）
  ['POST', '/work-orders/:id/dynamic-code', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status === 'DONE') throw { code: 1003, message: '当前状态不允许生成动态码' }
    return d.issueDynamicCode(o.id)
  }],
  ['POST', '/work-orders/:id/dynamic-code/verify', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    d.requireDynamicCode(o, body.code)
    return { ok: true }
  }],
  // ── 配合人候选与指派（2026-11-20 区域抢单池设计 D3/D4/D8/M5/M10，后端 WorkOrderService 同口径）──
  // 配合人候选：仅主维保本人（1403）；候选 = 工单 regionId 快照同区域（快照空=不限区域 → 全部）
  // ∧ 持平台ID（D4）∧ 在职 ∧ 排除主维保本人（M10：与池可见性同用 regionId 快照）
  ['GET', '/work-orders/:id/partner-candidates', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const me = currentEmployee()
    if (!me.platformId || me.platformId !== o.workerPlatformId) {
      throw { code: 1403, message: '仅主维保本人可查看配合人候选' }
    }
    const list = Object.keys(d.db.employees).map((k) => d.db.employees[k])
      .filter((e) => partnerEligible(o, e))
      .map((e) => ({ id: e.id, name: e.name, platformId: e.platformId, phone: e.phone }))
    return { list }
  }],
  // 指派/改选配合人：仅主维保本人（1403）；PENDING/PROCESSING 且配合人未签到才可改选（1003）；
  // 候选人服务端复核与 partner-candidates 同一谓词（不信任前端列表），不符 422
  ['POST', '/work-orders/:id/assign-partner', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const me = currentEmployee()
    if (!me.platformId || me.platformId !== o.workerPlatformId) {
      throw { code: 1403, message: '仅主维保本人可指定配合人' }
    }
    if (o.status !== 'PENDING' && o.status !== 'PROCESSING') {
      throw { code: 1003, message: '当前状态不允许指定配合人' }
    }
    if (o.assistantCheckinExtra) throw { code: 1003, message: '配合人员已签到，不可改选' }
    const cand = Object.keys(d.db.employees).map((k) => d.db.employees[k])
      .find((e) => e.id === String((body && body.employeeId) || ''))
    if (!partnerEligible(o, cand)) {
      throw { code: 422, message: '该员工不在配合人候选范围（需与本工单同区域且已完成平台人员登记）' }
    }
    o.assistantName = cand.name
    o.assistantPlatformId = cand.platformId
    return { ok: true, assistantName: cand.name, assistantPlatformId: cand.platformId }
  }],
  ['GET', '/work-orders/:id/checklist', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const el = d.getElevator(o.elevatorId) || {}
    return {
      checklistId: o.id,
      workTypeCode: o.workTypeCode || '',
      workType: o.workType || '',
      elevatorName: el.elevatorName || '',
      projectName: el.projectName || '',
      items: o.checklist
    }
  }],
  ['POST', '/work-orders/:id/checklist/:itemId', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const item = o.checklist.find((i) => i.id === params.itemId)
    if (!item) throw { code: 1404, message: '检查项不存在' }
    // 服务端校验（docs/04 A.2）
    if (body.result === 'NA' && !body.skipReason) {
      throw { code: 422, message: '「' + item.name + '」标记不适用时必须填写跳过原因（TSG 注 A-1）' }
    }
    if (body.result === 'ABNORMAL' && !body.abnormalDesc) {
      throw { code: 422, message: '「' + item.name + '」为异常时必须填写异常描述' }
    }
    if (body.result === 'ABNORMAL' && !(body.photoFileIds || []).length && !(body.photoUrls || []).length) {
      throw { code: 422, message: '「' + item.name + '」为异常时必须至少附 1 张照片' }
    }
    // 异常项必须显式记录隐患判定（S0—S7）；S0 表示该项异常但不构成严重事故隐患
    if (body.result === 'ABNORMAL') {
      const pc = String(body.problemCode == null ? '' : body.problemCode).trim()
      if (!pc) {
        throw { code: 422, message: '「' + item.name + '」为异常时必须记录隐患判定（S0—S7），不得留空' }
      }
      if (['S0', 'S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7'].indexOf(pc) < 0) {
        throw { code: 422, message: '「' + item.name + '」隐患码非法：' + pc + '（取值范围 S0—S7）' }
      }
    }
    // 关键项（试验/测试/校验/检测类，TSG 注A-2）执行时须照片留证；
    // 结果为"不适用"（NA，如该电梯无此部件）时豁免——部件不存在无从拍照（docs/08 BUG 修复）
    // ⚠ 必须用 body.result（本次选择），item.result 是旧状态：新填项为空串，恒 !== 'NA'，导致 BUG 复现
    if (item.isKey && item.photoRequired && body.result !== 'NA' &&
        !(body.photoFileIds || []).length && !(body.photoUrls || []).length) {
      throw { code: 422, message: '关键项「' + item.name + '」为试验/测试/校验/检测类，必须至少附 1 张照片留证（TSG 注A-2）' }
    }
    const updated = d.updateChecklistItem(params.id, params.itemId, {
      result: body.result,
      value: body.value != null ? Number(body.value) : null,
      valueText: body.valueText || '',
      abnormalDesc: body.abnormalDesc || '',
      skipReason: body.skipReason || '',
      problemCode: body.problemCode || '',
      photos: body.photoUrls || [], // mock 演示回显（本地路径）；真实后端只落 photoFileIds
      photoFileIds: body.photoFileIds || [],
      recordedAt: body.recordedAt || formatTime()
    })
    if (!updated) throw { code: 1404, message: '检查项不存在' }
    return { ok: true, itemId: params.itemId }
  }],
  // 周期性条目"本次仍要执行"（docs/03 §6.1：灰显不阻断，允许人工点选执行）
  ['POST', '/work-orders/:id/checklist/:itemId/run-this-time', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const item = o.checklist.find((i) => i.id === params.itemId)
    if (!item) throw { code: 1404, message: '检查项不存在' }
    item.notInThisRun = false
    return { ok: true }
  }],
  ['POST', '/work-orders/:id/signature', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '当前状态不允许签字（请先完成签到）' }
    const role = body.role === 'ASSISTANT' ? 'ASSISTANT' : 'PRINCIPAL'
    if (role === 'ASSISTANT') {
      o.assistantSignatureFileId = body.signatureFileId || ''
      o.assistantSignatureUrl = body.signatureUrl || ''
    } else {
      o.workerSignatureFileId = body.signatureFileId || ''
      o.workerSignatureUrl = body.signatureUrl || ''
    }
    return { ok: true, role }
  }],
  ['POST', '/work-orders/:id/checkout', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到' }
    // ── D8 签退恒双人门禁（后端 WorkOrderService.checkout 同口径，平台 2.6 workMan2Id 必填）──
    // 池化新单（regionId 快照非空 或 认领打标 claimedViaPool）：未选配合人/配合人未签到一律 422，
    // 先于签字与检查项校验、不产生任何写副作用；存量在途工单（M8）不触发，维持旧口径
    const pooled = !!o.regionId || !!(o.checkinExtra && o.checkinExtra.claimedViaPool)
    if (pooled) {
      if (!o.assistantPlatformId) {
        throw { code: 422, message: '请选择配合人员（双人作业，未选配合人不得签退）' }
      }
      if (!o.assistantCheckinExtra) {
        throw { code: 422, message: '配合人员未签到，无法签退（双人作业须双人签到留痕）' }
      }
    }
    // 签字已在各自手机完成（docs/01 §3.7.5）：签退前校验双签名齐全（后端同口径）
    if (!o.workerSignatureFileId) throw { code: 422, message: '请完成主维保签字' }
    if (o.assistantName && o.assistantName !== '无' && !o.assistantSignatureFileId) {
      throw { code: 422, message: '请完成配合人员签字（双人签字）' }
    }
    // 周期性"本次无需执行"条目不计入未完成项（docs/03 §7 验收）
    const mustRun = o.checklist.filter((i) => !i.notInThisRun)
    const unfinished = mustRun.filter((i) => !i.result).length
    if (unfinished > 0) throw { code: 1003, message: '还有 ' + unfinished + ' 项检查未填写' }
    // 关键项照片留证校验（TSG 注A-2）
    // "不适用"（NA）豁免：部件不存在的关键项无须照片（docs/08 BUG 修复）
    const keyNoPhoto = mustRun.filter(
      (i) => i.isKey && i.photoRequired && i.result && i.result !== 'NA' &&
        !((i.photoFileIds || []).length || (i.photos || []).length)
    )
    if (keyNoPhoto.length) {
      throw { code: 422, message: '关键项「' + keyNoPhoto[0].name + '」须至少附 1 张照片留证（TSG 注A-2），无法签退' }
    }
    // 兜底防御：异常项缺隐患判定时拒绝签退，不得静默报 S0（与后端同一口径）
    const abnormalUncoded = mustRun.filter((i) => i.result === 'ABNORMAL' && !i.problemCode)
    if (abnormalUncoded.length) {
      throw { code: 1003, message: '检查项「' + abnormalUncoded[0].name + '」为异常但未记录隐患判定，无法签退' }
    }
    // 业务规则：签到—签退间隔不少于 N 分钟（config.minWorkDurationMinutes，业主补充规则）
    const minutes = (Date.now() - parseTime(o.checkinTime)) / 60000
    if (minutes < config.minWorkDurationMinutes) {
      throw {
        code: 422,
        message: '作业时长不足 ' + config.minWorkDurationMinutes + ' 分钟（当前 ' + Math.floor(minutes) + ' 分钟），请继续作业后再签退'
      }
    }
    // 设备信息档案门禁（用户 2026-11-19，与后端 DeviceProfileValidator 同口径）：
    // 电梯编码/设备代码/注册代码/单位内编号/出厂编号为上传维保记录的必传项
    const el = d.getElevator(o.elevatorId)
    const deviceRequired = [
      ['elevatorCode', '电梯编码'], ['deviceCode', '设备代码'], ['registrationCode', '注册代码'],
      ['insideNumber', '单位内编号'], ['factoryNumber', '出厂编号']
    ]
    const deviceMissing = deviceRequired
      .filter(([k]) => !String((el && (k === 'registrationCode' ? el.regCode : el[k])) || '').trim())
      .map(([, label]) => label)
    if (deviceMissing.length) {
      throw {
        code: 422,
        message: '设备信息档案缺少上传维保记录的必传项：' + deviceMissing.join('、') +
          '；请先在 电梯台账 › 设备信息档案 补全后再上传维保记录'
      }
    }
    const r = d.markCheckout(o.id, body)
    // 响应对齐 docs/04 A.2 checkout
    return {
      workOrderId: o.id,
      duration: r.duration,
      originalRecordId: r.originalRecordId,
      reportStatus: r.reportStatus,
      // 签名确认链接参数（用户需求：完成后生成链接，安全管理员远程签字或本机代签）
      recordId: r.id,
      shareToken: r.shareToken
    }
  }],

  // ── 平台写链路（P3）：手动重报 2.6，仅 FAILED 记录（AGENTS §2.3 不自动重试）──
  ['POST', '/platform/records/:id/reupload', ({ params }) => {
    const rec = d.reuploadRecord(params.id)
    if (!rec) throw { code: 1404, message: '维保记录不存在' }
    return { ok: true, id: rec.id, reportStatus: rec.reportStatus }
  }],

  // ── 救援（已裁模块，默认 403）──
  ['POST', '/rescues', ({ body }) => { requireLegacyModule(); return d.createRescue(body) }],
  ['GET', '/rescues', ({ query }) => { requireLegacyModule(); return paginate(d.db.rescues, query) }],
  ['GET', '/rescues/:id', ({ params }) => { requireLegacyModule(); return findOr404(d.db.rescues, params.id, '救援记录') }],

  // ── 故障 ──
  ['POST', '/faults', ({ body }) => d.createFault(body)],
  ['GET', '/faults', ({ query }) => {
    let list = d.db.faults
    if (query && query.status) {
      list = list.filter((f) => f.status === query.status)
    }
    // 困人急修过滤（C-18/V62，工作台「急修待接」KPI 下钻口径，与后端 listFaults 一致）
    if (query && query.trapped === '1') {
      list = list.filter((f) => (f.trappedCount || 0) > 0)
    } else if (query && query.trapped === '0') {
      list = list.filter((f) => (f.trappedCount || 0) <= 0)
    }
    return paginate(list, query)
  }],
  ['GET', '/faults/:id', ({ params }) => findOr404(d.db.faults, params.id, '故障记录')],
  ['POST', '/faults/:id/close', ({ params, body }) => {
    const f = d.closeFault(params.id, body)
    if (!f) throw { code: 1404, message: '故障记录不存在' }
    return { ok: true }
  }],
  ['POST', '/admin/faults/:id/dispatch', ({ params, body }) =>
    d.dispatchFault(params.id, body && body.workerId)],

  // ── 合规台账（TSG 法定项，数据暂存本地不上报平台；已裁模块，默认 403）──
  ['GET', '/drills', () => { requireLegacyModule(); return d.listDrills() }],
  ['POST', '/drills', ({ body }) => { requireLegacyModule(); return d.createDrill(body) }],
  ['GET', '/inspects', () => { requireLegacyModule(); return d.listInspects() }],
  ['GET', '/inspects/template', ({ query }) => {
    requireLegacyModule()
    // 自行检查项 = 该电梯品种对应附件的年度维保项并集；不同品种分别取 A/B/C/D（docs/01 §3.17）
    const el = query && query.elevatorId ? d.getElevator(query.elevatorId) : null
    const appendix = d.CATEGORY_APPENDIX[el && el.category] || 'A'
    const items = []
    d.FREQ_CHAIN.OY.forEach(function (freq) {
      d.APPENDIX_TPLS[appendix][freq].forEach(function (it) {
        items.push(it)
      })
    })
    return { appendix: appendix, items: items }
  }],
  ['POST', '/inspects', ({ body }) => { requireLegacyModule(); return d.createInspect(body) }],

  // ── 消息 ──
  // V2.80：员工消息中心只读 useUnitId 为空的行（useUnitId 非空=客户门户受众，两侧互不串扰）
  ['GET', '/messages', ({ query }) => {
    const list = d.db.messages.filter((m) => !m.useUnitId).sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    return paginate(list, query)
  }],
  ['GET', '/messages/unread-count', () => (
    { count: d.db.messages.filter((m) => !m.useUnitId && !m.read).length }
  )],
  ['POST', '/messages/:id/read', ({ params }) => {
    const m = findOr404(d.db.messages, params.id, '消息')
    m.read = true
    return { ok: true }
  }],
  ['POST', '/messages/read-all', () => {
    d.db.messages.forEach((m) => { if (!m.useUnitId) m.read = true })
    return { ok: true }
  }],

  // ── 使用单位确认（客户门户，P1 T7 门禁 + 白名单，逐条对齐后端 UnitRecordService/UnitPortalService）──
  // 路由顺序：精确路径 /unit/records/list 必须排在 /unit/records/:id 之前，否则 list 被当作 :id（1404）
  ['GET', '/unit/records/list', () => {
    const unitId = requirePortalUnit()
    return clientVisibleRecords(unitId).map(clientRecordRow)
  }],
  // 客户待签列表（后端 pendingAs）：本单位 + 唯一可签状态 + confirmStatus=PENDING + 链末端最新版本 + 白名单
  ['GET', '/unit/records/pending', ({ query }) => {
    const unitId = requireClientUnit()
    const codes = ownElevatorCodes(unitId)
    const list = (d.db.unitRecords || [])
      .filter((r) => codes.has(r.elevatorCode) && isSignableRow(r))
      .map(toClientRecord)
    return paginate(list, query)
  }],
  // V2.80 批量签字（后端 UnitRecordService.batchConfirmAs 同口径）：同一签名一次确认 ≤20 条，
  // 逐条独立门禁（本单位 + 可签状态 + 先签为准），单条失败不拖累其余。
  // 路由顺序：必须排在 /unit/records/:id 之前（同段数模板先匹配先中）
  ['POST', '/unit/records/batch-confirm', ({ body }) => {
    const unitId = requireClientUnit()
    const ids = Array.from(new Set((((body && body.recordIds) || []).map((x) => String(x || '').trim())).filter(Boolean)))
    if (!ids.length) throw { code: 422, message: '请选择要签署的记录' }
    if (ids.length > 20) throw { code: 422, message: '单次最多批量签署 20 条记录' }
    if (!String((body && body.signatureUrl) || '') && !String((body && body.signatureFileId) || '')) {
      throw { code: 422, message: '请先完成签名' }
    }
    const codes = ownElevatorCodes(unitId)
    let signed = 0
    const failed = []
    ids.forEach((id) => {
      const r = (d.db.unitRecords || []).find((x) => x.id === id)
      try {
        if (!r) throw { code: 1404, message: '维保记录不存在' }
        if (!codes.has(r.elevatorCode)) throw { code: 1403, message: '仅本单位记录可确认' }
        requireSignable(r)
        signRecord(r, body)
        signed++
      } catch (e) {
        failed.push({ id, message: (e && e.message) || '签署失败' })
      }
    })
    return { ok: !failed.length, signed, failed }
  }],
  ['GET', '/unit/records/:id', ({ params }) => {
    // 门禁顺序与后端 getAs 一致：requireClient 最先——不存在的 id 也不得以 1404 泄漏存在性。
    // C-24：员工记录详情移至内部端点 GET /records/:id，本端点还原客户专用契约（后端员工令牌 1403）。
    const unitId = requireClientUnit()
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    // 归属（跨单位 1403 无副作用）→ 可见性（§4.6 可见性表）→ 白名单投影
    if (recordUnitId(r) !== unitId) throw { code: 1403, message: '仅本单位记录可查看' }
    requireViewable(r)
    return toClientRecord(r)
  }],
  // ── 员工内部读记录详情（C-24，后端 RecordController GET /records/{id} 同口径） ──
  // record:view 门禁（Y=MAINT_SUP/MAINT_MGR/PLATFORM_ADMIN，C=MAINT_TECH）→ V7 班组读谓词 →
  // 内部全量投影（含 lifecycleStatus/version/shareToken/context/reportPayload/items 明细，
  // pkg-maintenance/record-detail 依赖）；只回浅拷贝，不外泄 db 共享单例引用。
  ['GET', '/records/:id', ({ params }) => {
    // 门禁最先：无 record:view 的会话（安装部/商务/客户）读任何 id（含不存在）一律 1403，不以 1404 泄漏存在性
    requireRead('record:view')
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    requireRecordScope(r)
    return Object.assign({}, r)
  }],
  ['POST', '/unit/records/:id/confirm', ({ params, body }) => {
    const unitId = requireClientUnit()
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    if (recordUnitId(r) !== unitId) throw { code: 1403, message: '仅本单位记录可确认' }
    requireSignable(r)
    return signRecord(r, body) // 先签为准；satisfaction 不接收（§4.7 评价关闭）
  }],
  // 签名链接确认（用户需求：无需登录，凭一次性令牌远程签字或本机代签）；token 401 先于状态披露
  ['GET', '/unit/records/:id/sign-view', ({ params, query }) => {
    const r = findOr401Token(d.db.unitRecords, params.id) // C-25⑧：不存在 → 401 同错 token 文案
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    // 与后端 signView 同口径：白名单摘要 + 检查项明细（过滤本轮未执行项）+ 现场照片墙 +
    // 使用单位/维保日期/确认状态（2026-11-20 签署核对扩项，docs/14 §4.7）；
    // 不返回 satisfaction（§4.7）、不回显 shareToken
    const items = (r.items || []).filter(function (i) { return !i.notInThisRun }).map(function (i) {
      return {
        name: i.name, result: i.result, value: i.value, valueUnit: i.valueUnit,
        valueText: i.valueText, abnormalDesc: i.abnormalDesc, skipReason: i.skipReason,
        freqLabel: i.freqLabel, photos: i.photos || []
      }
    })
    const itemPhotos = []
    items.forEach(function (i) { (i.photos || []).forEach(function (p) { itemPhotos.push(p) }) })
    const photos = itemPhotos.length ? itemPhotos : (r.photos || [])
    return {
      confirmed: r.confirmStatus === 'CONFIRMED',
      confirmStatus: r.confirmStatus || '',
      elevatorName: r.elevatorName || '',
      elevatorCode: r.elevatorCode || '',
      useUnitName: r.useUnitName || '',
      workDate: (r.checkoutTime || r.checkinTime || '').slice(0, 10),
      workType: r.workType || '',
      workerName: r.workerName || '',
      assistantName: r.assistantName || '',
      checkinTime: r.checkinTime || '',
      checkoutTime: r.checkoutTime || '',
      duration: r.duration || '',
      items: items,
      itemTotal: items.length,
      photos: photos,
      photoCount: photos.length,
      // mock 演示字段（原型「记录单版本号 / SHA-256 前 8 位」；后端 P1 无对应列）
      recordVersion: r.recordVersion || '',
      hashPrefix: r.hashPrefix || '',
      frozenAt: r.frozenAt || '',
      signatureUrl: r.signatureUrl || '',
      // V2.80 签字 SLA 倒计时（sign 页；serverNow 供前端对齐时钟）
      signRequestedAt: signSlaFields(r).signRequestedAt,
      slaHours: CLIENT_SIGN_SLA_HOURS,
      signDeadline: signSlaFields(r).signDeadline,
      overdue: signSlaFields(r).overdue,
      serverNow: formatTime()
    }
  }],
  // 后端 confirmByToken（T4-C1）：token → 签字门禁链 → 条件更新；已签/旧版本一律 422，不再回 already 幂等体
  ['POST', '/unit/records/:id/confirm-by-token', ({ params, query, body }) => {
    const r = findOr401Token(d.db.unitRecords, params.id) // C-25⑧：不存在 → 401 同错 token 文案
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    requireSignable(r)
    return signRecord(r, body)
  }],
  // 后端 signatureUploadByToken（V2.80 修复）：免登录 token 链签名图上传，同门禁（校验先于"写盘"）
  ['POST', '/unit/records/:id/signature-upload', ({ params, query }) => {
    const r = findOr401Token(d.db.unitRecords, params.id)
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    requireSignable(r)
    return { fileId: 'mock_file_' + Date.now(), url: '' }
  }],

  // ── 知识库（已裁模块，默认 403）──
  ['GET', '/knowledge', ({ query }) => {
    requireLegacyModule()
    let list = d.db.knowledge
    if (query && query.keyword) {
      const k = String(query.keyword).toLowerCase()
      list = list.filter((x) => x.title.toLowerCase().indexOf(k) > -1)
    }
    return { list, total: list.length }
  }],

  // ── 文件（真实上传走 wx.uploadFile，此路由兜底）──
  ['POST', '/files/upload', () => ({ fileId: 'mock_file_' + Date.now(), url: '' })],
  // ── 移交单 / 外接建档 / 使用单位门户 / 维保日历（Task 12/13/14 最小 mock）──
  ['GET', '/handovers', ({ query }) => {
    requireRead('handover:view')
    // 维保部读收敛（与后端 HandoverService.list 同口径，2026-11-20 真机反馈③）：
    // 纯维保部角色默认隐藏安装内部阶段，显式 status 查询尊重入参
    const maintOnly = currentRoles.some((r) => r.indexOf('MAINT') === 0) &&
      !currentRoles.some((r) => r.indexOf('INST') === 0 || r === 'INSTALLER' || r === 'PLATFORM_ADMIN' || r === 'ADMIN')
    const VISIBLE = ['INSPECTING', 'RECTIFYING', 'PENDING_CONFIRM', 'ARCHIVED']
    const explicit = !!(query && query.status)
    return paginate(mockHandovers.filter((h) => explicit ? h.status === query.status : !(maintOnly && VISIBLE.indexOf(h.status) < 0)), query)
  }],
  ['POST', '/handovers', ({ body }) => {
    requireWrite('handover:create') // 2026-11-21 对齐后端：仅 INST_PM（矩阵 §4.1）
    const o = Object.assign({ id: 'ho_' + Date.now(), handoverNo: 'HO' + Date.now(), status: 'DOC_SUBMITTING',
      createdAt: formatTime(new Date()), checks: [], rectifications: [], signs: [] }, body)
    mockHandovers.unshift(o)
    return o
  }],
  ['GET', '/handovers/:id', ({ params }) => {
    requireRead('handover:view')
    return findOr404(mockHandovers, params.id, '移交单')
  }],
  ['POST', '/handovers/:id/submit-docs', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    // 与后端 HandoverService.submitDocs 同口径：3 平铺必传字段缺一 422，提交后进入内部审核
    const sr = body.supervisionReportFileId; const mm = body.maintenanceManualFileId; const qc = body.qualityCertificateFileId
    if (!sr || !mm || !qc) throw { code: 422, message: '监督检验报告、维保说明书、整机质量证明缺一不可' }
    Object.assign(o, { supervisionReportFileId: sr, maintenanceManualFileId: mm, qualityCertificateFileId: qc, status: 'PENDING_INST_REVIEW' })
    return o
  }],
  ['POST', '/handovers/:id/review', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const action = String(body.action || '').toUpperCase()
    if (action === 'REJECT') {
      if (!body.remark) throw { code: 422, message: '审核退回须填写原因' }
      if (!body.deadline) throw { code: 422, message: '审核退回须填写整改期限' }
      Object.assign(o, { status: 'DOC_SUBMITTING', reviewRemark: body.remark, reviewDeadline: body.deadline, reviewedAt: formatTime(new Date()) })
    } else {
      Object.assign(o, { status: 'INSPECTING', reviewedAt: formatTime(new Date()) })
    }
    return o
  }],
  ['POST', '/handovers/:id/void', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (o.status === 'ARCHIVED' || o.status === 'VOIDED') throw { code: 422, message: '已归档或已作废的移交单不可作废' }
    if (!body.reason) throw { code: 422, message: '作废须填写原因' }
    Object.assign(o, { status: 'VOIDED', voidReason: body.reason, voidedAt: formatTime(new Date()) })
    return o
  }],
  ['POST', '/handovers/:id/inspect', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const items = (body.items || []).map((it, i) => Object.assign({ id: 'hi_' + i }, it))
    o.checks = [{ id: 'hc_' + Date.now(), checkerName: body.checkerName, checkedAt: formatTime(new Date()), items }].concat(o.checks || [])
    const force = items.some((it) => (it.itemType === 'SAFETY_DEVICE' || it.itemType === 'BRAKE') && it.result === 'FAIL')
    o.status = force ? 'RECTIFYING' : 'PENDING_CONFIRM'
    return o
  }],
  ['POST', '/handovers/:id/rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    o.rectifications = (o.rectifications || []).concat((body.rectifications || []).map((r, i) => Object.assign({ id: 'hr_' + Date.now() + i }, r)))
    return o
  }],
  ['POST', '/handovers/:id/recheck-rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    o.rectifications = (o.rectifications || []).map((r) => Object.assign({}, r, { recheckResult: body.result }))
    o.status = body.result === 'PASS' ? 'PENDING_CONFIRM' : 'RECTIFYING'
    return o
  }],
  ['POST', '/handovers/:id/confirm', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    Object.assign(o, body, { status: 'ARCHIVED', confirmedAt: formatTime(new Date()) })
    return o
  }],
  // ── C-12：74 项移交检查清单 / 整改清单 / 归档目录（docs/15、docs/04 A.9）──
  ['GET', '/handovers/:id/checklist', ({ params }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (!o.checklist74) o.checklist74 = buildHandoverChecklist()
    return { check: { id: 'hc_' + o.id, handoverId: o.id, totalItems: 74, conclusion: o.checkConclusion || null,
      passCount: (o.checkCounts && o.checkCounts.pass) || 0, failCount: (o.checkCounts && o.checkCounts.fail) || 0,
      naCount: (o.checkCounts && o.checkCounts.na) || 0, importantFailCount: (o.checkCounts && o.checkCounts.impFail) || 0 }, items: o.checklist74 }
  }],
  ['POST', '/handovers/:id/checklist/submit', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (!o.checklist74) o.checklist74 = buildHandoverChecklist()
    const sub = {}
    ;(body.items || []).forEach((s) => { sub[s.itemNo] = s })
    let pass = 0, fail = 0, na = 0, impFail = 0
    const fails = []
    o.checklist74.forEach((it) => {
      const s = sub[it.itemNo] || {}
      if (!s.result) throw { code: 422, message: '第 ' + it.itemNo + ' 项未填结果' }
      it.result = s.result
      it.measuredValue = s.measuredValue || null
      it.failDesc = s.failDesc || null
      it.naReason = s.naReason || null
      it.photoFileIds = s.photoFileIds || null
      if (it.result === 'FAIL') {
        if (!it.failDesc) throw { code: 422, message: '第 ' + it.itemNo + ' 项不合格须填描述' }
        if ((it.important || it.itemNo >= 57) && !it.photoFileIds) throw { code: 422, message: '第 ' + it.itemNo + ' 项不合格须附照片' }
        fail++; if (it.important) impFail++; fails.push(it)
      } else if (it.result === 'NA') {
        if (!it.naReason) throw { code: 422, message: '第 ' + it.itemNo + ' 项不适用须选原因码' }
        na++
      } else if (it.result === 'PASS') {
        if (it.itemNo >= 57 && !it.measuredValue) throw { code: 422, message: '第 ' + it.itemNo + ' 项试验须填实测值' }
        pass++
      } else throw { code: 422, message: '第 ' + it.itemNo + ' 项结果须 PASS/FAIL/NA' }
    })
    const conclusion = judgeHandover(impFail, fail - impFail, body.conditional)
    o.checkConclusion = conclusion
    o.checkCounts = { pass, fail, na, impFail }
    if (conclusion === 'RECTIFY' || conclusion === 'DISQUALIFIED') {
      o.rectifications = fails.map((f, i) => ({ id: 'hr_' + Date.now() + i, handoverId: o.id, itemNo: f.itemNo,
        itemType: f.itemGroup, respParty: '安装单位', source: 'INSPECT', voided: false,
        beforePhotoFileId: f.photoFileIds, recheckResult: null }))
      o.status = 'RECTIFYING'
    } else {
      o.status = 'PENDING_CONFIRM'
    }
    return o
  }],
  ['GET', '/handovers/:id/rectifications', ({ params }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    return (o.rectifications || []).slice().sort((a, b) => (a.itemNo || 0) - (b.itemNo || 0))
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const r = (o.rectifications || []).find((x) => x.id === params.rectId)
    if (!r) throw { code: 1404, message: '整改记录不存在' }
    Object.assign(r, { rectMeasure: body.rectMeasure, afterPhotoFileId: body.afterPhotoFileId,
      rectifierName: body.rectifierName, rectifiedAt: formatTime(new Date()) })
    return r
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/recheck', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const r = (o.rectifications || []).find((x) => x.id === params.rectId)
    if (!r) throw { code: 1404, message: '整改记录不存在' }
    Object.assign(r, { recheckResult: body.result, recheckerId: body.recheckerId,
      recheckerName: body.recheckerName, recheckedAt: formatTime(new Date()) })
    const all = (o.rectifications || []).filter((x) => !x.voided)
    const allPass = all.length > 0 && all.every((x) => x.recheckResult === 'PASS')
    o.status = allPass ? 'PENDING_CONFIRM' : 'RECTIFYING'
    return o
  }],
  ['POST', '/handovers/:id/na-approve', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const s = { id: 'hs_' + Date.now(), handoverId: o.id, nodeType: 'NA_APPROVE',
      actorId: body.approverId, actorName: body.approverName, action: 'APPROVE', at: formatTime(new Date()) }
    o.signs = (o.signs || []).concat(s)
    return s
  }],
  ['GET', '/handovers/:id/export-pdf', ({ params, query }) => {
    findOr404(mockHandovers, params.id, '移交单')
    return { form: (query && query.form) || 'CHECK', fileUrl: '', note: 'mock 不生成真实 PDF，真实端点返回二进制 application/pdf' }
  }],
  ['GET', '/elevators/:id/archive', ({ params }) => handoverArchiveEntries(params.id)],
  ['GET', '/handovers/:id/archive', ({ params }) => {
    findOr404(mockHandovers, params.id, '移交单')
    return handoverArchiveEntries(params.id)
  }],
  ['GET', '/filings', ({ query }) => {
    requireRead('filing:view') // 2026-11-21 对齐后端：Y=PA/MM/MS/MB，C=MQ/IM/IP/IC
    return paginate(mockFilings.filter((f) => !query.status || f.status === query.status), query)
  }],
  ['POST', '/filings', ({ body }) => {
    requireWrite('filing:write') // 2026-11-21 对齐后端：仅 PA/MM/MS/MB（矩阵 M0 §2.2）
    const o = Object.assign({ id: 'ef_' + Date.now(), filingNo: 'EF' + Date.now(), status: 'DOC_COLLECTING',
      createdAt: formatTime(new Date()), docs: [], takeoverChecks: [], confirmation: null }, body)
    mockFilings.unshift(o)
    return o
  }],
  ['GET', '/filings/:id', ({ params }) => {
    requireRead('filing:view')
    return findOr404(mockFilings, params.id, '外接建档单')
  }],
  ['POST', '/filings/:id/submit-docs', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.docs = (body.docs || []).map((x, i) => Object.assign({ id: 'fd_' + i }, x))
    o.status = 'TAKEOVER_CHECKING'
    return o
  }],
  ['POST', '/filings/:id/takeover-check', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.takeoverChecks = [{ id: 'tc_' + Date.now(), checkerName: body.checkerName, checkedAt: formatTime(new Date()), items: (body.items || []).map((it, i) => Object.assign({ id: 'ti_' + i }, it)) }]
    o.status = 'PENDING_CONFIRM'
    return o
  }],
  ['POST', '/filings/:id/confirm', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.confirmation = Object.assign({ id: 'hcn_' + Date.now(), at: formatTime(new Date()) }, body)
    o.status = 'ARCHIVED'
    return o
  }],
  // ── 以下门户契约 V41 废弃（单账号单会话，unit_token 废除）：后端保留至生产清库（docs/12 G 组），
  //    前端已无调用者（2026-11-16 审查清理），mock 仅作契约镜像保留，勿新增入口 ──
  ['POST', '/unit/accounts', () => ({ account: 'U' + Date.now(), initialPassword: 'Mock12345678', inviteCode: 'MOCK2345' })],
  ['POST', '/unit/login', ({ body }) => {
    if (!body.account || !body.password) throw { code: 422, message: '请填写账号与密码' }
    const unit = (d.db.useUnits || [])[0] || { id: 'uu_1', unitName: '演示使用单位' }
    return { token: 'mock-unit-token', account: body.account, useUnitId: unit.id, unitName: unit.unitName, role: 'UNIT_ADMIN', wechatBound: false }
  }],
  ['POST', '/unit/bind-wechat', () => ({ bound: true })],
  ['POST', '/unit/login-by-wechat', () => {
    const unit = (d.db.useUnits || [])[0] || { id: 'uu_1', unitName: '演示使用单位' }
    return { token: 'mock-unit-token', useUnitId: unit.id, unitName: unit.unitName, role: 'UNIT_ADMIN', wechatBound: true }
  }],
  ['POST', '/unit/account/password', ({ body }) => {
    if (!body.oldPassword) throw { code: 422, message: '原口令不正确' }
    if (!body.newPassword || String(body.newPassword).length < 6) throw { code: 422, message: '新口令至少 6 位' }
    return { ok: true }
  }],
  // ── 使用单位门户数据域（live，后端 UnitPortalController/UnitPortalService）：一律会话锚点本单位，
  //    P1 T7 起不再回退 useUnits[0]、不再全量返回；非客户/无锚点 403 fail-closed ──
  ['GET', '/unit/elevators', () => {
    const unitId = requirePortalUnit()
    return (d.db.elevators || []).filter((el) => el.useUnitId === unitId && el.status !== 'INACTIVE').map((el) => ({
      id: el.id, elevatorCode: el.elevatorCode, elevatorName: el.elevatorName, projectName: el.projectName,
      location: el.location, insideNumber: el.insideNumber, regCode: el.regCode, deviceCode: el.deviceCode,
      nextMaintenanceDate: el.nextMaintenanceDate || '', nextCheckDate: el.nextCheckDate || ''
    }))
  }],
  // 待签记录（后端 pendingSigns = records 按可签集合过滤）；行投影兼容期保留 shareToken（裁决 T7-C2，
  // docs/14 §4.6 规则 8）——客户待办「去签字」需凭此拼签字链接，sign 页无会话回退路径
  ['GET', '/unit/pending-signs', () => {
    const unitId = requirePortalUnit()
    return clientVisibleRecords(unitId).filter(isSignableRow).map(clientPendingSignRow)
  }],
  // ── 客户门户消息（V2.80 签字临期/超期提醒，后端 ClientMessageService 同口径；数据域=本单位锚点）──
  ['GET', '/unit/messages', () => {
    const unitId = requirePortalUnit()
    return (d.db.messages || []).filter((m) => m.useUnitId === unitId)
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)).slice(0, 50)
      .map((m) => ({ id: m.id, title: m.title || '', content: m.content || '', createdAt: m.createdAt || '', readFlag: !!m.readFlag }))
  }],
  ['GET', '/unit/messages/unread-count', () => {
    const unitId = requirePortalUnit()
    return { count: (d.db.messages || []).filter((m) => m.useUnitId === unitId && !m.readFlag).length }
  }],
  ['POST', '/unit/messages/:id/read', ({ params }) => {
    const unitId = requirePortalUnit()
    const m = findOr404(d.db.messages, params.id, '消息')
    if (m.useUnitId !== unitId) throw { code: 1403, message: '仅可操作本单位消息' }
    m.readFlag = true
    return { ok: true }
  }],
  ['POST', '/unit/messages/read-all', () => {
    const unitId = requirePortalUnit()
    let updated = 0
    ;(d.db.messages || []).forEach((m) => { if (m.useUnitId === unitId && !m.readFlag) { m.readFlag = true; updated++ } })
    return { ok: true, updated }
  }],
  // 客户急修列表（C-25② 契约对齐）：后端投影白名单 id/faultNo/elevatorCode/elevatorName/faultType/desc/
  // status/createdAt/finishedAt/result——elevatorName 按 elevatorCode 批量联电梯（一次 .in，无 N+1），
  // 缺名回退 elevatorCode；mock 回整行（字段超集），种子行 elevatorName 口径一致。
  // trappedCount 已随 V62 在员工侧落地（建单/列表/KPI）；客户门户投影白名单暂不含（不下发）。
  ['GET', '/unit/faults', () => {
    const unitId = requirePortalUnit()
    const codes = ownElevatorCodes(unitId)
    return (d.db.faults || []).filter((f) => codes.has(f.elevatorCode)).slice(0, 50)
  }],
  // 使用单位报修（后端 reportFault）：电梯必须属本单位，越权 1403 且不落单
  ['POST', '/unit/faults', ({ body }) => {
    const unitId = requirePortalUnit()
    const code = String((body && body.elevatorCode) || '').trim()
    if (!code) throw { code: 422, message: '请选择电梯' }
    if (!String((body && body.desc) || '').trim()) throw { code: 422, message: '请填写故障描述' }
    if (!ownElevatorCodes(unitId).has(code)) throw { code: 1403, message: '仅可为本单位电梯报修' }
    return d.createFault(body)
  }],
  // 维保日历（后端 MaintenanceCalendarService 同口径，2026-11-20 建档即排全年）：
  // 锚点=最早 HM 工单计划日 → 下次到期日 → 建档基线 lastMaintenanceAt+intervalDays；
  // 锚点起按累加式 15/90/180/365 投影窗口内应做日期，条目带执行状态。mock 不做人员范围过滤（演示全量）。
  ['GET', '/maintenance-calendar', ({ query }) => {
    const STATUS_TEXT = { PLANNED: '计划', DISPATCHED: '已派单', DONE: '已完成', OVERDUE: '超期' }
    const TYPE_TEXT = { HM: '半月维保', TM: '季度维保', SM: '半年维保', OY: '年度维保' }
    const DAY = 86400000
    const start = query.from ? new Date(String(query.from).replace(/-/g, '/')) : new Date()
    const end = query.to ? new Date(String(query.to).replace(/-/g, '/')) : new Date(start.getTime() + 365 * DAY)
    const dayStr = (t) => formatTime(new Date(t)).slice(0, 10)
    const today = dayStr(Date.now())
    const typeAt = (days) => {
      if (days < 0) return null
      if (days === 0 || days % 365 === 0) return days === 0 ? 'HM' : 'OY'
      if (days % 180 === 0) return 'SM'
      if (days % 90 === 0) return 'TM'
      if (days % 15 === 0) return 'HM'
      return null
    }
    const entries = []
    ;(d.db.elevators || []).forEach((el) => {
      if (el.status === 'INACTIVE') return
      const cfg = el.maintenance || {}
      const hm = (d.db.orders || []).filter((o) => o.elevatorId === el.id && o.workTypeCode === 'HM' && o.planTime)
        .sort((a, b) => (a.planTime < b.planTime ? -1 : 1))[0]
      let anchor = null
      if (hm) anchor = String(hm.planTime).slice(0, 10)
      else if (el.nextMaintenanceDate) anchor = String(el.nextMaintenanceDate).slice(0, 10)
      else if (cfg.lastMaintenanceAt) anchor = dayStr(parseTime(cfg.lastMaintenanceAt) + (cfg.intervalDays || 15) * DAY)
      if (!anchor) return
      const anchorMs = new Date(anchor.replace(/-/g, '/')).getTime()
      for (let t = start.getTime(); t <= end.getTime(); t += DAY) {
        const code = typeAt(Math.round((t - anchorMs) / DAY))
        if (!code) continue
        const date = dayStr(t)
        const sameDay = (o) => o.elevatorId === el.id && o.planTime && String(o.planTime).slice(0, 10) === date
        const order = (d.db.orders || []).find((o) => sameDay(o) && o.status === 'DONE') || (d.db.orders || []).find(sameDay)
        const status = order && order.status === 'DONE' ? 'DONE' : order ? 'DISPATCHED' : date < today ? 'OVERDUE' : 'PLANNED'
        entries.push({
          date, elevatorId: el.id, elevatorCode: el.elevatorCode, elevatorName: el.elevatorName,
          projectName: el.projectName || '', location: el.location || '', anchorDate: anchor,
          workTypeCode: code, workType: TYPE_TEXT[code],
          status, statusText: STATUS_TEXT[status],
          orderId: order ? order.id : '', orderNo: order ? (order.orderNo || '') : ''
        })
      }
    })
    entries.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : (a.elevatorName < b.elevatorName ? -1 : 1)))
    return { from: dayStr(start.getTime()), to: dayStr(end.getTime()), entries }
  }]
]

// ── 路径匹配 ──

function matchPath(pattern, path) {
  const ps = pattern.split('/').filter(Boolean)
  const xs = path.split('/').filter(Boolean)
  if (ps.length !== xs.length) return null
  const params = {}
  for (let i = 0; i < ps.length; i++) {
    if (ps[i].charAt(0) === ':') {
      params[ps[i].slice(1)] = decodeURIComponent(xs[i])
    } else if (ps[i] !== xs[i]) {
      return null
    }
  }
  return params
}

function parseQuery(queryStr) {
  const query = {}
  if (!queryStr) return query
  queryStr.split('&').forEach((kv) => {
    const idx = kv.indexOf('=')
    if (idx > -1) query[kv.slice(0, idx)] = decodeURIComponent(kv.slice(idx + 1))
  })
  return query
}

function mockRequest(options) {
  const method = String(options.method || 'GET').toUpperCase()
  const raw = String(options.url || '')
  const qIdx = raw.indexOf('?')
  const path = qIdx > -1 ? raw.slice(0, qIdx) : raw
  // 修复：GET 的筛选参数在 options.data（wx.request 约定），必须与 URL 查询串合并，
  // 否则 status/due/keyword 等永远到不了 listOrders（BUG：状态筛选不过滤）
  const query = Object.assign(
    {},
    (options.data && typeof options.data === 'object') ? options.data : {},
    parseQuery(qIdx > -1 ? raw.slice(qIdx + 1) : '')
  )

  return new Promise((resolve, reject) => {
    // 模拟 150ms 网络延迟，便于观察 loading 态
    setTimeout(() => {
      for (let i = 0; i < routes.length; i++) {
        const r = routes[i]
        if (r[0] !== method) continue
        const params = matchPath(r[1], path)
        if (!params) continue
        try {
          resolve(r[2]({ params, query, body: options.data || {} }))
        } catch (e) {
          reject(e && e.code ? e : { code: 500, message: (e && e.message) || 'Mock 内部错误' })
        }
        return
      }
      reject({ code: 404, message: 'Mock 接口未实现: ' + method + ' ' + path })
    }, 150)
  })
}

module.exports = { mockRequest }
