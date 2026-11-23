const d = require('./data')
const { formatTime, parseTime, distanceMeters } = require('../utils/util')
const config = require('../config/index')
const menus = require('../config/menus')

// mock 无 JWT 解析：登录成功后记住当前演示角色，供自助改密等"当前用户"接口使用
let currentRole = 'WORKER'
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
function isCustomerPrincipal(roles) {
  const list = roles || []
  if (!list.length) return false
  return list.every((r) => {
    const c = permMirror.canonical(r)
    return c === 'CLIENT_ADMIN' || c === 'CLIENT_SIGNER'
  })
}
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
// 客户可见生命周期集合：待签可签 + 三态只读
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
    recordVersion: r.recordVersion || '', hashPrefix: r.hashPrefix || '', frozenAt: r.frozenAt || ''
  }
}
const CLIENT_SIGN_SLA_HOURS = 72
function signSlaFields(r) {
  const base = r.signRequestedAt || r.checkoutTime || r.createdAt || ''
  if (!base) return { signRequestedAt: '', signDeadline: '', overdue: false }
  const deadline = formatTime(new Date(parseTime(base) + CLIENT_SIGN_SLA_HOURS * 3600000))
  return { signRequestedAt: base, signDeadline: deadline, overdue: parseTime(deadline) < Date.now() }
}
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
// 签字落库（后端 UnitRecordService.signRecord 同口径，confirmAs/confirmByToken 共用，门禁须已前置通过）：先签为准（条件更新等价判定，已签 → 422，不覆盖首签）；
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
const LEGACY_MODULES_ENABLED = false
function requireLegacyModule() {
  if (!LEGACY_MODULES_ENABLED) throw { code: 403, message: '该模块已裁剪停用（docs/01 §10.2），恢复需开启 APP_LEGACY_MODULES_ENABLED' }
}
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
// 与后端同缺口径：规范码主角色（如 MAINT_MGR/PLATFORM_ADMIN）不落旧码集合 → 走本人兜底（fail-closed）；
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
// 记录归属校验（后端 RecordController 同谓词，WorkOrderService.scopeOrders 同款）：null=管理角色不限；
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
function partnerEligible(o, e) {
  if (!e || !e.platformId) return false
  if (e.enabled === false) return false
  if (e.platformId === o.workerPlatformId) return false
  return o.regionId == null || o.regionId === '' || o.regionId === e.regionId
}
// 移交 mock 状态（模块级，进程重启即清空）
const mockHandovers = []

// mock 无调度器，仅在模块加载时执行一次，读接口不得再调用 ensureDueOrders
d.ensureDueOrders()

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

function findOr401Token(list, id) {
  const item = list.find((x) => x.id === id)
  if (!item) throw { code: 401, message: '确认链接无效或已失效' }
  return item
}

const HANDOVER_IMPORTANT = new Set([2, 3, 5, 8, 9, 10, 12, 13, 16, 18, 20, 21, 22, 23, 24, 25, 26, 29, 30, 32, 33, 35, 36, 37, 38, 39, 42, 43, 45, 46, 47, 48, 49, 50, 51, 52, 53, 58, 62, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73])
const HANDOVER_TEMPLATE = [
  [1, 'G01', '5.1.1', '通道', '通往机器空间/滑轮间通道符合 GB/T 7588.1 5.2.2；畅通、安全、有照明，无杂物积水'],
  [2, 'G01', '5.1.2', '机器空间和滑轮间的要求', '旋转部件上方净距≥0.30m；滑轮间活动区净高≥1.50m；控制柜前净深≥0.70m；工作区净高≥2.10m；地面平整防滑；悬挂点标工作负荷'],
  [3, 'G01', '5.1.3', '主开关、照明及其开关', '主开关符合 5.10.5.1/5.10.5.2；切断供电后不能自动操作；照明照度符合 5.2.1.4/5.10.8（照度计实测）'],
  [4, 'G01', '5.1.4', '警告和说明', '警告与说明设置符合 5.2.6.2'],
  [5, 'G01', '5.1.5', '断相、错相保护和电动机电源切断检查', '断任一相停止并保持；交换两相不能原方向运行；两独立接触器串联监测故障防再运行'],
  [6, 'G01', '5.1.6', '电气布线及安装', '动力/控制线路分开或屏蔽；接地黄绿双色单独接至接地端不串接；软管固定≤1m；防护≥IP2X；绝缘电阻合格'],
  [7, 'G01', '5.1.7', '接触器和接触器式继电器', '型号 AC-15/DC-13；固定可靠接线牢固；标识与原理图一致'],
  [8, 'G01', '5.1.8', '设备安装', '悬挂装置与楼板孔间隙 20—40mm、圈框高出≥50mm；限速器绳轮垂直度≤2/1000；动作速度整定封记完好'],
  [9, 'G01', '5.1.9', '驱动主机', '机电式摩擦型制动、持续通电保持松开；制动部件分两组独立制动；静态制动力矩试验合格；手动盘车/紧急操作有效'],
  [10, 'G01', '5.1.10', '旋转部件的防护', '曳引轮/滑轮/链轮/限速器/张紧轮设防护防≥2.5mm 异物；外侧面局部涂黄；手动释放部件涂红'],
  [11, 'G01', '5.1.11', '电动机和其他电气设备的保护', '供电短路保护；电动机过热保护；其他电气保护符合 GB/T 5226.1 7.1—7.4'],
  [12, 'G01', '5.1.12', '电动机运转时间限制器', '不启动或下行阻停时按限值切断主机供电；只能手动复位；动作后不影响检修/紧急电动'],
  [13, 'G01', '5.1.13', '紧急操作', '停电/故障停开锁区外时移动轿厢措施有效；手动紧急操作有效；可确认轿厢到达开锁区域'],
  [14, 'G02', '5.2.1', '井道壁', '井道封闭；玻璃井道壁/底/顶设标记用夹层玻璃；底坑下方空间尺寸强度符合 5.2.5.2/5.2.5.3'],
  [15, 'G02', '5.2.2', '通道门、安全门、通道活板门和检修门', '设置与强度符合 5.2.3'],
  [16, 'G02', '5.2.3', '安全空间和安全间距', '轿厢与对重运动部件间距≥50mm；轿顶/底坑避险空间符合 5.2.5.7/5.2.5.8'],
  [17, 'G02', '5.2.4', '导轨', '每根导轨≥2 支架间距≤2.5m；垂直度每5m 轿厢0.6mm/对重1.0mm；接头缝隙≤0.5mm、台阶≤0.05mm；不得焊接'],
  [18, 'G02', '5.2.5', '对重和平衡重', '对重块完好固定无移位；防压裂/脱落装置；数量标记易识别'],
  [19, 'G02', '5.2.6', '随行电缆', '两端固定；压缩缓冲器后不接触平台/底框/底坑；无打结扭曲；不与限速器绳/极限开关干涉'],
  [20, 'G02', '5.2.7', '限速器', '绳至导轨面偏差≤10mm；动作速度符合 5.6.2.2.1；绳断裂/伸长电气安全装置动作；可触发安全钳'],
  [21, 'G02', '5.2.8', '缓冲器', '轿厢/对重底部均设缓冲器；蓄能型≤1.0m/s；耗能型行程符合公式；未复位前不能启动；液压充液量符合设计'],
  [22, 'G02', '5.2.9', '底坑', '通道门/梯子符合 5.2.2.4；设停止装置/检修控制/插座/照明；对重运行区隔障；下方有人空间对重设安全钳'],
  [23, 'G03', '5.3.1', '机器在机房内', '井道内曳引机时确认可在机房检查/测试/维护；机房布置尺寸符合 5.1.2'],
  [24, 'G03', '5.3.2', '机器在井道内', '机器空间/工作区尺寸符合 5.1.2；紧急/测试操作装置能在井道外操作'],
  [25, 'G03', '5.3.3', '机器在井道外', '机器柜无其他管槽电缆；无孔壁/底/顶/门；门不能向柜内开、有钥匙锁'],
  [26, 'G03', '5.3.4', '紧急和测试操作装置', '按 GB/T 10059 5.1.19 检查/试验/测量，符合 5.2.6.6'],
  [27, 'G03', '5.3.5', '滑轮间的结构和设备', '滑轮间开口/空间符合 5.2.6.7；无滑轮间置 NA-01'],
  [28, 'G04', '5.4.1', '轿厢总体', '净高≥2.0m；有效面积与载重/乘客数符合 5.4.2；地板水平度≤3/1000；通风孔≥1% 且 φ10 棒不能穿过'],
  [29, 'G04', '5.4.2', '轿厢护脚板', '护脚板结构与强度符合 5.4.5'],
  [30, 'G04', '5.4.3', '轿门', '轿门无孔封闭完整；阻止关门力≤150N；关门保护有效；每个轿门关闭位有电气安全装置；啮合深度有标识'],
  [31, 'G04', '5.4.4', '轿厢玻璃', '夹层玻璃强度符合 5.4.3.2；1.10m 以下玻璃扶手高 0.90—1.10m 且与玻璃无关'],
  [32, 'G04', '5.4.5', '轿顶', '设检修控制/停止装置/插座；防滑与强度符合 5.4.7.1；护栏/踢脚板符合 5.4.7.2—4'],
  [33, 'G04', '5.4.6', '轿厢安全窗和轿厢安全门', '净尺寸≥0.40×0.50m；锁紧电气安全装置有效；三角钥匙轿内可开；开启不超轿厢外缘'],
  [34, 'G04', '5.4.7', '应急照明', '正常照明失效自动点亮；容量/照度符合 5.4.10.4'],
  [35, 'G04', '5.4.8', '安全钳', '设于轿厢下行方向；触发/释放符合 5.6.2.2；渐进式封记完好；动作后地板倾斜≤5%'],
  [36, 'G04', '5.4.9', '轿厢上行超速保护装置', '减速部件作用位置符合 5.6.6.4；动作后制停或降至对重缓冲器范围；驱动能量缺失时停止保持'],
  [37, 'G04', '5.4.10', '轿厢意外移动保护装置', '设置符合 5.12.1.4；制停部件能制停保持；制停距离在型式证书范围；自监测符合 5.6.7.3'],
  [38, 'G05', '5.5.1', '悬挂装置', '≥两根钢丝绳/包覆绳/链条；异常伸长电气安全装置停止；直径≥曳引轮节圆40倍；端接合规防松；承载/寿命监测'],
  [39, 'G05', '5.5.2', '补偿装置', '补偿装置符合 5.5.6；包覆绳审查型式证明；张紧轮节圆/承载直径比≥30'],
  [40, 'G05', '5.5.3', '强制驱动电梯钢丝绳的卷绕', '卷绕符合 5.5.4；非强制驱动置 NA-02'],
  [41, 'G06', '5.6.1', '层站指示和操作装置', '指示/呼梯安装位置符合设计、信号清晰操作准确；集选/群控符合 5.12.4.3'],
  [42, 'G06', '5.6.2', '层站处运行间隙和安装尺寸', '地坎高出装修面2—5mm；轿厢/层门地坎水平偏差+3/0mm≤35mm；门锁滚轮与门刀啮合≥5mm；门扇间隙符合'],
  [43, 'G06', '5.6.3', '层门运行相关的保护', '阻止关门力≤150N；锁紧啮合≥7mm；开锁区域符合 5.3.8.1；每个层门关闭位电气安全装置；紧急开锁符合 5.9.3.3'],
  [44, 'G06', '5.6.4', '耐火层门', '耐火时间≥建筑要求；型式报告/证书适用范围内；建筑无耐火要求置 NA-03'],
  [45, 'G07', '5.7.1', '电气装置的安装检查', '接线正确固定牢靠；不因正常碰撞/绳摆动产生位移损坏误动作'],
  [46, 'G07', '5.7.2', '电气安全装置的作用方式', '任一电气安全装置动作→主机不能启动/立即停止并切断制动器供电'],
  [47, 'G07', '5.7.3', '电气安全装置的型式', '型式符合 5.11.2'],
  [48, 'G08', '5.8', '紧急报警装置', '管理组织及时响应轿厢/井道报警；一键通话；行程>30m 设对讲；紧急电源；避险空间可操作'],
  [49, 'G09', '5.9.1', '门未关闭和未锁紧情况下的平层、再平层和预备操作控制', '具有该功能时符合 5.12.1.4；不具备置 NA-01'],
  [50, 'G09', '5.9.2', '检修运行控制', '装置位置符合 5.12.1.5.1.1；功能符合 5.12.1.5.2；按钮型式/信息符合'],
  [51, 'G09', '5.9.3', '紧急电动运行控制', '按 GB/T 10059 5.1.7.2 检查试验，符合 5.12.1.6'],
  [52, 'G09', '5.9.4', '层门和轿门旁路装置', '按 GB/T 10059 5.1.5.2 检查试验，符合 5.12.1.8'],
  [53, 'G09', '5.9.5', '门触点电路故障监测', '按 GB/T 10059 5.1.5.4 试验；故障时防止正常运行监测符合 5.12.1.9'],
  [54, 'G10', '5.10', '电梯数据信息输出', '输出方式符合 5.11.3；未配置置 NA-01'],
  [55, 'G11', '5.11', '消防员电梯的附加要求', '增加 GB/T 26465 对应验收项目附页逐项记录条款号；非消防员置 NA-04；不内置子项（B3）'],
  [56, 'G12', '5.12', '防爆电梯的附加要求', '增加 GB/T 31094 对应验收项目附页逐项记录条款号；非防爆置 NA-04；不内置子项（B3）'],
  [57, 'G13', '6.1', '速度', '按 GB/T 10059 5.2.1.1 测速，符合 GB/T 10058 4.3.1；填实测 m/s'],
  [58, 'G13', '6.2', '平衡系数', '按 5.2.1.2 试验，符合 4.3.8；填实测值'],
  [59, 'G13', '6.3', '启动加速度、制动减速度和 A95 加速度、A95 减速度', '按 5.2.2 试验，符合 4.3.2/4.3.3；填实测 m/s²'],
  [60, 'G13', '6.4', '振动', '按 5.2.6 试验，符合 4.3.5；填实测值'],
  [61, 'G13', '6.5', '开关门时间', '按 5.2.4 试验，符合 4.3.4；填实测 s'],
  [62, 'G13', '6.6', '平层准确度和平层保持精度', '按 5.2.3 试验，符合 4.3.7；填实测 mm'],
  [63, 'G13', '6.7', '运行噪声', '按 5.2.5 试验，符合 4.3.6；填实测 dB(A)'],
  [64, 'G13', '6.8', '载荷控制', '按 5.1.15 试验，符合 GB/T 7588.1 5.12.1.2'],
  [65, 'G13', '6.9', '制动系统', '按 5.1.11 试验，符合 GB/T 10058 4.5.2c)/f)'],
  [66, 'G13', '6.10', '曳引能力', '按 5.1.14.2/3 试验，符合 GB/T 7588.1 6.3.4'],
  [67, 'G13', '6.11', '限速器与安全钳', '按 5.1.2 或制造单位方法联动试验；试验后无影响正常使用损坏'],
  [68, 'G13', '6.12', '轿厢上行超速保护装置', '按 5.1.6 试验，使轿厢制停或降至对重缓冲器设计范围'],
  [69, 'G13', '6.13', '轿厢意外移动保护装置', '按 5.1.16 试验，符合 GB/T 7588.1 5.6.7'],
  [70, 'G13', '6.14', '曳引式电梯的其他制动装置', '按 5.1.12 试验，符合 GB/T 7588.1 4.19；无其他制动装置置 NA-02'],
  [71, 'G13', '6.15', '缓冲器', '按 5.1.3 试验，符合 GB/T 7588.1 5.8'],
  [72, 'G13', '6.16', '层门与轿门的关闭', '按 5.1.5.1 试验，符合 GB/T 7588.1 5.3.9.3.4/5.3.9.4/5.3.13'],
  [73, 'G13', '6.17', '极限开关', '按 5.1.4 试验，符合 GB/T 7588.1 5.12.2'],
  [74, 'G13', '6.18', '运行考核', '空载/额定载重各运行1000次（每天≥8h），符合 GB/T 10058 4.28']
]
function buildHandoverChecklist() {
  return HANDOVER_TEMPLATE.map(function (t) {
    return { id: 'hi_' + t[0], itemNo: t[0], itemGroup: t[1], clauseNo: t[2], itemName: t[3],
      requirement: t[4], important: HANDOVER_IMPORTANT.has(t[0]), result: null,
      measuredValue: null, failDesc: null, naReason: null, photoFileIds: null }
  })
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
function handoverArchiveEntries(id) {
  const cats = [['01', 'CHECKLIST'], ['02', 'RECTIFICATION'], ['03', 'DOCS'], ['04', 'PHOTOS'],
    ['05', 'CONFIRMATION'], ['06', 'FIRST_ORDER'], ['07', 'PENDING_DOCS']]
  return cats.map((c, i) => ({ id: 'ha_' + id + '_' + c[0], handoverId: id,
    category: c[1], seq: i + 1, sha256: 'mock_sha256_' + c[0], archivedAt: formatTime(new Date()) }))
}

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

function loginPayload(roleKey) {
  const user = d.db.employees[roleKey]
  const canon = permMirror.canonical(roleKey) || roleKey
  const roles = (user.roles && user.roles.length ? user.roles : [canon]).map((r) => permMirror.canonical(r) || r)
  currentRoles = roles
  const perms = permsOf(roles)
  const ws = menus.activeWorkspace(roles)
  const scope = roles.indexOf('PLATFORM_ADMIN') > -1 ? '全部数据'
    : isCustomerPrincipal(roles) ? '本单位全部电梯'
      : roles.some((r) => r.indexOf('INST') === 0) ? '安装部项目范围' : '本班组维保范围'
  const userInfo = Object.assign({}, user)
  delete userInfo.password
  return {
    token: 'mock-token-' + Date.now(), userInfo, role: roleKey,
    roles, perms, scope, activeWorkspace: ws, home: menus.resolveHome(roles),
    principalType: principalTypeOf(roles)
  }
}

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
  ['GET', '/projects', () => ({ list: (d.db.projects || []).slice(0, 50), total: (d.db.projects || []).length })],
  ['GET', '/contracts', () => ({ list: (d.db.contracts || []).slice(0, 50), total: (d.db.contracts || []).length })],
  ['GET', '/users', () => ({ list: Object.keys(d.db.employees).map((k) => { const e = d.db.employees[k]; return { id: e.id, name: e.name, account: e.account, role: e.role, roleText: e.roleText, groupName: e.groupName, enabled: e.enabled !== false } }), total: Object.keys(d.db.employees).length })],
  ['GET', '/employees', () => Object.keys(d.db.employees).map((k) => { const e = d.db.employees[k]; return { id: e.id, name: e.name, role: e.role === 'MAINT_TECH' ? 'WORKER' : e.role, groupName: e.groupName, enabled: e.enabled !== false } })],
  ['GET', '/use-units', () => d.db.useUnits],
  // 手机号互斥 1002 对齐后端 checkUseUnit：负责人手机 ≠ 安全管理员手机。
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
  ['GET', '/me/menus', ({ query }) => {
    const roles = currentRoles.length ? currentRoles : ['MAINT_TECH']
    const effective = query.workspace && roles.indexOf(query.workspace) > -1 ? [query.workspace] : roles
    const ws = menus.activeWorkspace(effective)
    const actions = {}
    const BUTTON_CODES = require('../services/menu').BUTTON_CODES
    BUTTON_CODES.forEach((c) => { actions[c] = effective.some((r) => permMirror.allowsNew(r, c)) })
    const has = (r) => effective.indexOf(r) > -1
    let quickActions = []
    if (ws && ws.indexOf('MAINT') === 0) {
      quickActions = [
        { key: 'emergency', text: '急修单', icon: 'bolt', path: '/pages/order/list/index?state=urgent' },
        { key: 'report', text: '报修接单', icon: 'wrench', path: '/pages/order/list/index?state=pending' },
        { key: 'handover', text: '移交验收', icon: 'file-signature', path: '/pkg-handover/pages/list/index' }
      ]
      if (has('MAINT_SUP') || has('MAINT_MGR')) quickActions.push({ key: 'plan', text: '维保计划', icon: 'calendar-check-r', path: '/pkg-maintenance/pages/plan/index' })
    } else if (ws && ws.indexOf('CLIENT') === 0) {
      quickActions = []
      if (permsOf(effective).indexOf('client:report') > -1) {
        quickActions.push({ key: 'report', text: '一键报修', icon: 'wrench', path: '/pages/order/report/index' })
      }
      quickActions.push({ key: 'elevators', text: '我的电梯', icon: 'elevator', path: '/pages/elevator/list/index' })
      quickActions.push({ key: 'todo', text: '待办签字', icon: 'file-signature', path: '/pkg-client/pages/todo/index' })
    } else if (ws && ws.indexOf('INST') === 0) {
      if (has('INST_CLERK') || has('INST_PM')) quickActions.push({ key: 'create', text: '发起移交', icon: 'file-signature', path: '/pkg-handover/pages/create/index' })
      if (has('INST_CLERK') || has('INST_PM')) quickActions.push({ key: 'upload', text: '资料上传', icon: 'folder', path: '/pkg-handover/pages/upload/index' })
    }
    const badges = { pendingOrders: 0, pendingSigns: 0 }
    if (effective.some((r) => r.indexOf('MAINT') === 0)) {
      badges.pendingOrders = d.listOrders({}).filter(orderVisible).filter((o) => o.status !== 'DONE').length
    }
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
    rec.signRequestedAt = formatTime()
    rec.signRemindedAt = null
    return { ok: true, shareToken: rec.shareToken || '' }
  }],
  ['GET', '/work-orders/:id/archive', () => ({ url: 'https://mock/files/arch?s=mock', hash: '0'.repeat(64), archivedAt: '2026-11-15 00:00:00' })],
  ['POST', '/unit/records/:id/refuse-by-token', ({ params, query, body }) => {
    const r = findOr401Token(d.db.unitRecords, params.id) // C-25⑧：不存在 → 401 同错 token 文案
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    if (r.lifecycleStatus !== 'PENDING_CLIENT_SIGNATURE') throw { code: 422, message: '记录不在待使用单位签署状态' }
    if (r.confirmStatus !== 'PENDING') throw { code: 422, message: '记录已签署，不能拒签' }
    if (!isLatestVersion(r)) throw { code: 422, message: '记录已被新版本替代，请处理最新记录' }
    // 未传码按兼容期回退「原因必填」
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
  ['POST', '/auth/change-password', ({ body }) => {
    const user = currentClient || d.db.employees[currentRole]
    if (!user) throw { code: 401, message: '登录状态无效，请重新登录' }
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
  // 本人信息（与后端 AuthController.me 同形状：员工=userInfo 全量档案字段，客户=简表 + principalType）
  ['GET', '/auth/me', () => {
    if (currentClient) {
      const a = currentClient
      return { userInfo: { id: a.id, name: a.name, phone: a.phone, useUnitId: a.useUnitId, status: a.status }, role: a.role, principalType: 'CUSTOMER' }
    }
    const user = d.db.employees[currentRole] || d.db.employees.WORKER
    const userInfo = Object.assign({}, user)
    delete userInfo.password
    return { userInfo, role: currentRole }
  }],

  // ── 工单 ──
  ['GET', '/home/summary', () => {
    const summary = d.getHomeSummary()
    summary.openOrders = (d.db.orders || []).filter((o) => orderVisible(o) && o.status !== 'DONE').length
    return summary
  }],
  ['GET', '/elevators', () => {
    const anchor = clientUnitAnchor()
    const list = d.listElevators()
    if (anchor === undefined) return list
    if (anchor === null) return []
    const ids = new Set(d.db.elevators.filter((el) => el.useUnitId === anchor).map((el) => el.id))
    return list.filter((row) => ids.has(row.id))
  }],
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
  ['PUT', '/elevators/:id/project-name', ({ params, body }) => {
    const el = d.getElevator(params.id)
    if (!el) throw { code: 1404, message: '电梯不存在' }
    const v = String((body && body.projectName) || '').trim()
    if (!v) throw { code: 422, message: '项目名称不能为空' }
    el.projectName = v
    el.elevatorName = v // 电梯名称 = 项目名称（小区），合并
    return { ok: true }
  }],
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
    if (!assistant && o.workerPlatformId && me.platformId && o.workerPlatformId !== me.platformId) {
      throw { code: 1003, message: '该工单已被 ' + (o.workerName || '其他维保人员') + ' 认领' }
    }
    if (assistant ? o.assistantCheckinExtra : o.checkinExtra) {
      throw { code: 1003, message: (assistant ? '配合人员' : '主维保人员') + '已签到，不可重复签到' }
    }
    const lat = Number(body.latitude)
    const lng = Number(body.longitude)
    if (!isFinite(lat) || !isFinite(lng)) {
      throw { code: 422, message: '签到必须携带定位坐标；定位失败请重试（严禁伪造坐标）' }
    }
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
    let claimedNow = false
    if (!assistant && !o.workerPlatformId) {
      if (!me.platformId) throw { code: 422, message: '请先在管理端完成平台人员登记' }
      o.workerName = me.name
      o.workerPlatformId = me.platformId
      claimedNow = true
    }
    // mock 只回显不拦截，拦截口径以后端为准
    const el = d.getElevator(o.elevatorId)
    const threshold = (el && el.checkinThreshold) || 200
    const raw = (el && el.lat && el.lng) ? distanceMeters(lat, lng, el.lat, el.lng) : -1
    const distance = raw < 0 ? -1 : Math.round(raw * 10) / 10
    const geoStatus = distance < 0 ? 'UNKNOWN' : 'PROVIDED'
    d.markCheckin(o.id, body, { distance: distance, threshold: threshold, geoStatus: geoStatus })
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
  // 主维保生成双人动态码
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
  // ── 配合人候选与指派──配合人候选：仅主维保本人（1403）；
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
  // 指派/改选配合人：仅主维保本人（1403）；
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
    // 结果为"不适用"（NA，如该电梯无此部件）时豁免——部件不存在无从拍照⚠ 必须用 body.result（本次选择），item.result 是旧状态：新填项为空串，恒 !== 'NA'，导致 BUG 复现
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
    const pooled = !!o.regionId || !!(o.checkinExtra && o.checkinExtra.claimedViaPool)
    if (pooled) {
      if (!o.assistantPlatformId) {
        throw { code: 422, message: '请选择配合人员（双人作业，未选配合人不得签退）' }
      }
      if (!o.assistantCheckinExtra) {
        throw { code: 422, message: '配合人员未签到，无法签退（双人作业须双人签到留痕）' }
      }
    }
    // 签字已在各自手机完成：签退前校验双签名齐全（后端同口径）
    if (!o.workerSignatureFileId) throw { code: 422, message: '请完成主维保签字' }
    if (o.assistantName && o.assistantName !== '无' && !o.assistantSignatureFileId) {
      throw { code: 422, message: '请完成配合人员签字（双人签字）' }
    }
    const mustRun = o.checklist.filter((i) => !i.notInThisRun)
    const unfinished = mustRun.filter((i) => !i.result).length
    if (unfinished > 0) throw { code: 1003, message: '还有 ' + unfinished + ' 项检查未填写' }
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
    const minutes = (Date.now() - parseTime(o.checkinTime)) / 60000
    if (minutes < config.minWorkDurationMinutes) {
      throw {
        code: 422,
        message: '作业时长不足 ' + config.minWorkDurationMinutes + ' 分钟（当前 ' + Math.floor(minutes) + ' 分钟），请继续作业后再签退'
      }
    }
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
    if (query && query.trapped === '1') {
      list = list.filter((f) => (f.trappedCount || 0) > 0)
    } else if (query && query.trapped === '0') {
      list = list.filter((f) => (f.trappedCount || 0) <= 0)
    }
    return paginate(list, query)
  }],
  ['GET', '/faults/:id', ({ params }) => findOr404(d.db.faults, params.id, '故障记录')],
  ['POST', '/faults/:id/close', () => {
    throw { code: 1403, message: '急修闭环须由使用单位确认，员工不可代签关闭' }
  }],
  ['POST', '/admin/faults/:id/dispatch', ({ params, body }) =>
    d.dispatchFault(params.id, body && body.workerId)],

  // ── 合规台账（TSG 法定项，数据暂存本地不上报平台；已裁模块，默认 403）──
  ['GET', '/drills', () => { requireLegacyModule(); return d.listDrills() }],
  ['POST', '/drills', ({ body }) => { requireLegacyModule(); return d.createDrill(body) }],
  ['GET', '/inspects', () => { requireLegacyModule(); return d.listInspects() }],
  ['GET', '/inspects/template', ({ query }) => {
    requireLegacyModule()
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
    const unitId = requireClientUnit()
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    // 归属（跨单位 1403 无副作用）→ 可见性（§4.6 可见性表）→ 白名单投影
    if (recordUnitId(r) !== unitId) throw { code: 1403, message: '仅本单位记录可查看' }
    requireViewable(r)
    return toClientRecord(r)
  }],
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
      recordVersion: r.recordVersion || '',
      hashPrefix: r.hashPrefix || '',
      frozenAt: r.frozenAt || '',
      signatureUrl: r.signatureUrl || '',
      signRequestedAt: signSlaFields(r).signRequestedAt,
      slaHours: CLIENT_SIGN_SLA_HOURS,
      signDeadline: signSlaFields(r).signDeadline,
      overdue: signSlaFields(r).overdue,
      serverNow: formatTime()
    }
  }],
  // 已签/旧版本一律 422，不再回 already 幂等体
  ['POST', '/unit/records/:id/confirm-by-token', ({ params, query, body }) => {
    const r = findOr401Token(d.db.unitRecords, params.id) // C-25⑧：不存在 → 401 同错 token 文案
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    requireSignable(r)
    return signRecord(r, body)
  }],
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
  ['GET', '/handovers', ({ query }) => {
    requireRead('handover:view')
    const maintOnly = currentRoles.some((r) => r.indexOf('MAINT') === 0) &&
      !currentRoles.some((r) => r.indexOf('INST') === 0 || r === 'INSTALLER' || r === 'PLATFORM_ADMIN' || r === 'ADMIN')
    const VISIBLE = ['INSPECTING', 'RECTIFYING', 'PENDING_CONFIRM', 'ARCHIVED']
    const explicit = !!(query && query.status)
    return paginate(mockHandovers.filter((h) => explicit ? h.status === query.status : !(maintOnly && VISIBLE.indexOf(h.status) < 0)), query)
  }],
  ['GET', '/handovers/options', () => {
    requireWrite('handover:create')
    return { useUnits: d.db.useUnits.map((u) => ({ id: u.id, unitName: u.unitName })) }
  }],
  ['POST', '/handovers', ({ body }) => {
    requireWrite('handover:create') // 2026-11-21 对齐后端：仅 INST_PM（矩阵 §4.1）
    if (!body.useUnitId || !d.db.useUnits.some((u) => u.id === body.useUnitId)) throw { code: 422, message: '使用单位不存在' }
    if (!String(body.factoryNumber || '').trim()) throw { code: 422, message: '厂家出厂编号为归档时 2.7 查询依据，不能为空' }
    if (!String(body.insideNumber || '').trim()) throw { code: 422, message: '单位内编号为设备档案必传项，不能为空' }
    if (!body.supervisionReportFileId || !body.maintenanceManualFileId || !body.qualityCertificateFileId) {
      throw { code: 422, message: '监督检验报告、维护使用说明书、安装质量证明文件缺一不可' }
    }
    const safeBody = Object.assign({}, body)
    delete safeBody.elevatorId
    const o = Object.assign({ id: 'ho_' + Date.now(), handoverNo: 'HO' + Date.now(), status: 'PENDING_INST_REVIEW',
      createdAt: formatTime(new Date()), checks: [], rectifications: [], signs: [] }, safeBody)
    mockHandovers.unshift(o)
    return o
  }],
  ['GET', '/handovers/:id', ({ params }) => {
    requireRead('handover:view')
    return findOr404(mockHandovers, params.id, '移交单')
  }],
  ['POST', '/handovers/:id/submit-docs', ({ params, body }) => {
    requireWrite('handover:doc_upload')
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
    requireWrite('handover:inspect')
    const o = findOr404(mockHandovers, params.id, '移交单')
    const items = (body.items || []).map((it, i) => Object.assign({ id: 'hi_' + i }, it))
    o.checks = [{ id: 'hc_' + Date.now(), checkerName: body.checkerName, checkedAt: formatTime(new Date()), items }].concat(o.checks || [])
    const force = items.some((it) => (it.itemType === 'SAFETY_DEVICE' || it.itemType === 'BRAKE') && it.result === 'FAIL')
    o.status = force ? 'RECTIFYING' : 'PENDING_CONFIRM'
    return o
  }],
  ['POST', '/handovers/:id/rectify', ({ params, body }) => {
    requireWrite('handover:rectify')
    const o = findOr404(mockHandovers, params.id, '移交单')
    o.rectifications = (o.rectifications || []).concat((body.rectifications || []).map((r, i) => Object.assign({ id: 'hr_' + Date.now() + i }, r)))
    return o
  }],
  ['POST', '/handovers/:id/recheck-rectify', ({ params, body }) => {
    requireWrite('handover:inspect')
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
  ['GET', '/handovers/:id/checklist', ({ params }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (!o.checklist74) o.checklist74 = buildHandoverChecklist()
    return { check: { id: 'hc_' + o.id, handoverId: o.id, totalItems: 74, conclusion: o.checkConclusion || null,
      passCount: (o.checkCounts && o.checkCounts.pass) || 0, failCount: (o.checkCounts && o.checkCounts.fail) || 0,
      naCount: (o.checkCounts && o.checkCounts.na) || 0, importantFailCount: (o.checkCounts && o.checkCounts.impFail) || 0 }, items: o.checklist74 }
  }],
  ['POST', '/handovers/:id/checklist/submit', ({ params, body }) => {
    requireWrite('handover:inspect')
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
    requireRead('handover:view')
    const o = findOr404(mockHandovers, params.id, '移交单')
    return (o.rectifications || []).slice().sort((a, b) => (a.itemNo || 0) - (b.itemNo || 0))
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/rectify', ({ params, body }) => {
    requireWrite('handover:rectify')
    const o = findOr404(mockHandovers, params.id, '移交单')
    const r = (o.rectifications || []).find((x) => x.id === params.rectId)
    if (!r) throw { code: 1404, message: '整改记录不存在' }
    Object.assign(r, { rectMeasure: body.rectMeasure, afterPhotoFileId: body.afterPhotoFileId,
      rectifierName: body.rectifierName, rectifiedAt: formatTime(new Date()) })
    return r
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/recheck', ({ params, body }) => {
    requireWrite('handover:inspect')
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
  ['GET', '/unit/elevators', () => {
    const unitId = requirePortalUnit()
    return (d.db.elevators || []).filter((el) => el.useUnitId === unitId && el.status !== 'INACTIVE').map((el) => ({
      id: el.id, elevatorCode: el.elevatorCode, elevatorName: el.elevatorName, projectName: el.projectName,
      location: el.location, insideNumber: el.insideNumber, regCode: el.regCode, deviceCode: el.deviceCode,
      nextMaintenanceDate: el.nextMaintenanceDate || '', nextCheckDate: el.nextCheckDate || ''
    }))
  }],
  // 行投影兼容期保留 shareToken——客户待办「去签字」需凭此拼签字链接，sign 页无会话回退路径
  ['GET', '/unit/pending-signs', () => {
    const unitId = requirePortalUnit()
    return clientVisibleRecords(unitId).filter(isSignableRow).map(clientPendingSignRow)
  }],
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
  ['GET', '/unit/faults', () => {
    const unitId = requirePortalUnit()
    return (d.db.faults || []).filter((f) => f.useUnitId === unitId).slice(0, 50)
  }],
  ['GET', '/unit/faults/:id', ({ params }) => {
    const unitId = requirePortalUnit()
    const f = findOr404(d.db.faults, params.id, '急修单')
    if (f.useUnitId !== unitId) throw { code: 1403, message: '仅本单位急修单可查看' }
    return f
  }],
  ['POST', '/unit/faults/:id/confirm', ({ params, body }) => {
    const unitId = requirePortalUnit()
    if (!currentRoles.includes('CLIENT_SIGNER')) throw { code: 1403, message: '需使用单位签字人角色' }
    const f = findOr404(d.db.faults, params.id, '急修单')
    if (f.useUnitId !== unitId) throw { code: 1403, message: '仅本单位急修单可确认' }
    if (f.status !== 'PENDING_CONFIRM') throw { code: 422, message: '仅待使用单位确认的急修单可确认' }
    const pass = !body || body.pass !== false
    if (!pass && !String((body && body.reason) || '').trim()) throw { code: 422, message: '拒绝原因必填' }
    f.status = pass ? 'CLOSED' : 'REFUSED'
    if (pass) f.confirmedAt = formatTime()
    else f.refuseReason = String(body.reason).trim()
    return f
  }],
  ['POST', '/unit/faults/:id/cancel', ({ params, body }) => {
    const unitId = requirePortalUnit()
    if (!currentRoles.includes('CLIENT_ADMIN')) throw { code: 1403, message: '仅使用单位管理员可取消' }
    const f = findOr404(d.db.faults, params.id, '急修单')
    if (f.useUnitId !== unitId) throw { code: 1403, message: '仅本单位急修单可取消' }
    if (f.status !== 'OPEN') throw { code: 1403, message: '接单后仅维保主管可取消' }
    if (!String((body && body.reason) || '').trim()) throw { code: 422, message: '取消原因必填' }
    f.status = 'CANCELLED'
    f.cancelReason = String(body.reason).trim()
    return f
  }],
  // 使用单位报修（后端 reportFault）：电梯必须属本单位，越权 1403 且不落单
  ['POST', '/unit/faults', ({ body }) => {
    const unitId = requirePortalUnit()
    const code = String((body && body.elevatorCode) || '').trim()
    if (!code) throw { code: 422, message: '请选择电梯' }
    if (!String((body && body.desc) || '').trim()) throw { code: 422, message: '请填写故障描述' }
    if (!ownElevatorCodes(unitId).has(code)) throw { code: 1403, message: '仅可为本单位电梯报修' }
    const created = d.createFault(Object.assign({}, body, { createdBy: 'unit:' + unitId }))
    const el = (d.db.elevators || []).find((x) => x.elevatorCode === code)
    const platformId = el && (el.workerPlatformId || (el.maintenance && el.maintenance.workerPlatformId))
    if (platformId) {
      const worker = Object.values(d.db.employees).find((e) => e.platformId === platformId)
      if (worker) return d.dispatchFault(created.id, worker.id)
    }
    return created
  }],
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
  // 修复：GET 的筛选参数在 options.data（wx.request 约定），必须与 URL 查询串合并，否则 status/due/keyword 等永远到不了 listOrders（BUG：状态筛选不过滤）
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
