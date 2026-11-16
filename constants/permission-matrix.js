// 权限矩阵唯一事实源镜像（逐字镜像后端 PermissionMatrix v2，spec §4.2）。
// 小程序 mock / Node 脚本 / admin（经 ui/permission-v2.js ESM 桥）共用，防手写漂移。
// 前端仅体验优化，后端唯一权威；fail-closed（未知角色 DENY、未知码抛错）。
const ROLE = {
  PLATFORM_ADMIN: 'PLATFORM_ADMIN', SYS_ADMIN: 'SYS_ADMIN', INST_MGR: 'INST_MGR',
  INST_PM: 'INST_PM', INST_CLERK: 'INST_CLERK', INSTALLER: 'INSTALLER',
  MAINT_MGR: 'MAINT_MGR', MAINT_SUP: 'MAINT_SUP', MAINT_TECH: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY',
  CLIENT_ADMIN: 'CLIENT_ADMIN', CLIENT_SIGNER: 'CLIENT_SIGNER'
}
const ALIAS = {
  ADMIN: 'PLATFORM_ADMIN', SYS_ADMIN: 'SYS_ADMIN', INSTALL_MANAGER: 'INST_PM',
  INSTALL_CLERK: 'INST_CLERK', INSTALL_WORKER: 'INSTALLER', MAINT_MANAGER: 'MAINT_MGR',
  LEADER: 'MAINT_SUP', WORKER: 'MAINT_TECH', ASSISTANT: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY', UNIT_ADMIN: 'CLIENT_ADMIN'
}
const SCOPE = {
  PLATFORM_ADMIN: 'ALL', SYS_ADMIN: 'ALL', INST_MGR: 'ORG', INST_PM: 'PROJECT',
  INST_CLERK: 'PROJECT', INSTALLER: 'SELF', MAINT_MGR: 'ALL', MAINT_QUALITY: 'ORG',
  MAINT_BIZ: 'ORG', MAINT_SUP: 'TEAM', MAINT_TECH: 'SELF', CLIENT_ADMIN: 'ORG_FLAT',
  CLIENT_SIGNER: 'ORG_FLAT'
}
const PA = 'PLATFORM_ADMIN', SA = 'SYS_ADMIN', IM = 'INST_MGR', IP = 'INST_PM', IC = 'INST_CLERK',
  IW = 'INSTALLER', MM = 'MAINT_MGR', MS = 'MAINT_SUP', MT = 'MAINT_TECH',
  MB = 'MAINT_BIZ', MQ = 'MAINT_QUALITY', CA = 'CLIENT_ADMIN', CS = 'CLIENT_SIGNER'
// code -> { role: 'Y'|'C' }（缺省 DENY）；逐字镜像 PermissionMatrix.STATES
const STATES = {}
function g(code, st, roles) {
  const row = STATES[code] || (STATES[code] = {})
  roles.forEach(r => { row[r] = st })
}
const y = (code, ...roles) => g(code, 'Y', roles)
const c = (code, ...roles) => g(code, 'C', roles)
// §4.1 安装移交（16）
// P1 T2（2026-11-16 裁决）：客户（CA/CS）收回全部内部码，最小集仅 §4.5 的 elevator_view/download/sign；
// 无参 y()/c() 仅登记码（持有者由 SYS_ADMIN 全 Y 循环兜底），86 码计数不变。
y('handover:view', IM, MS, MM, PA, SA); c('handover:view', IC, IP, IW, MT)
y('handover:create', IP)
y('handover:edit', IP); c('handover:edit', IC)
y('handover:doc_upload', IC, IP)
y('handover:doc_delete', IC, IP)
y('handover:submit', IP)
y('handover:review', IM)
y('handover:review_return', IM)
y('handover:receive', MS)
y('handover:inspect', IW); c('handover:inspect', MT)
y('handover:rectify', IP, IC, IW)
y('handover:sign_install', IM, IW)
y('handover:sign_maint', MS, MM)
y('handover:void', IM, MM, PA, SA)
y('handover:export', IM, MS, MM, PA, SA)
y('handover:notify_client', MS)
// §4.2 项目合同电梯（9）
y('project:view', MS, MM, PA, SA); c('project:view', IC, IP, IM)
y('project:edit', MM, PA, SA)
y('contract:view', MM, PA, SA, MB); c('contract:view', MS)
y('contract:edit', MM, PA, SA, MB)
c('elevator:view', PA, SA, IM, IP, IC, IW, MM, MS, MT, MB, MQ)
y('elevator:edit', MS, PA, SA)
y('elevator:qr', MT, MS)
y('elevator:transfer', MM, PA, SA)
y('elevator:export', MM, PA, SA); c('elevator:export', MS)
// §4.3 维保作业（15）
y('plan:view', MS, MM); c('plan:view', MT)
y('plan:generate', MS, MM)
y('task:accept', MT)
y('task:assign_partner', MS, MT)
y('checklist:fill', MT)
y('record:view', MS, MM, PA, SA); c('record:view', MT)
y('record:submit', MT)
y('record:sign_tech', MT)
y('record:review', MS, MM)
y('record:sign_request', MT, MS)
y('record:sign') // P1 T2：客户签署收敛到 client:sign；record:sign 仅 SYS_ADMIN 持有
y('record:refuse') // P1 T2：拒签能力 P3 增 client:refuse 前仅 SYS_ADMIN 持有（DC3）
y('record:archive', MS)
y('record:amend', MM, PA, SA)
y('record:export', MS, MM, PA, SA)
// §4.4 急修报修（14）
// P1 T2：客户报修默认关闭（client:report 收回；重开由 P3 裁决），仅 SYS_ADMIN 持有
y('order:create')
y('order:create_proxy', MS)
y('order:view_pool', MS, MM); c('order:view_pool', MT)
y('order:view_own') // P1 T2：仅 SYS_ADMIN 持有（客户工单读走锚点判定，不依赖本码）
y('order:grab', MT)
y('order:dispatch', MS, MM)
c('order:arrive', MT)
c('order:process', MT)
y('order:submit', MT)
y('order:confirm') // P1 T2：客户确认收敛到 client:sign；仅 SYS_ADMIN 持有
y('order:close', MS)
y('order:cancel', MS)
y('order:escalate', MM)
y('order:export', MS, MM, PA, SA)
// §4.5 使用单位（7）：P1 T2 客户最小能力集 = elevator_view/download/sign（CA+CS 均 Y，doc §5.4 两角色能力相同）；
// report/evaluate/user_manage/invite 对客户默认关闭（仅 SYS_ADMIN 持有，重开由后续裁决），invite 保留 MS。
y('client:invite', MS)
y('client:user_manage')
y('client:elevator_view', CA, CS)
y('client:report')
y('client:sign', CA, CS)
y('client:evaluate')
y('client:download', CA, CS)
// §4.6 组织权限审计（10）
y('user:view', PA, SA); c('user:view', IM, MS, MM)
y('user:create', IM, MS, MM, PA, SA)
y('user:disable', IM, MS, MM, PA, SA)
y('role:assign', PA, SA)
y('invite:manage', IM, MS, MM, PA, SA)
y('stats:view', IM, MS, MM, PA, SA)
y('stats:export', MM, PA, SA); c('stats:export', IM, MS)
y('audit:view', PA, SA)
y('config:manage', PA, SA)
y('data:export_sensitive', PA, SA)
// M0 §2.2 扩展（15）
y('report:view', PA, SA, MM); c('report:view', MS, MQ)
y('report:retry', PA, SA, MM)
// 用户裁决 2026-11-15：外接建档写归维保部（安装部保留条件读）
y('filing:view', PA, MM, MS, MB); c('filing:view', MQ, IM, IP, IC)
y('filing:write', PA, MM, MS, MB)
y('region:view', PA, SA)
y('region:write', PA, SA)
y('template:view', PA, SA, MM); c('template:view', MQ)
y('template:write', PA, SA, MM, MQ)
y('template:toggle', PA, SA, MQ)
y('platform:worker_view', PA, SA); c('platform:worker_view', IP, IC, MM, MS)
y('platform:worker_register', PA, SA, MM)
y('platform:sync', PA, SA)
y('record:spotcheck', MQ)
y('client:edit', PA, MB)
y('user:reset_password', PA, SA, MM, IM)

// 用户裁决 2026-11-15：SYS_ADMIN 拥有所有权限（负责开通所有人员账户）
Object.keys(STATES).forEach((code) => { STATES[code][SA] = 'Y' })

const CODES = new Set(Object.keys(STATES))
function canonical(anyCode) {
  if (anyCode == null) return null
  if (ROLE[anyCode]) return anyCode
  return ALIAS[anyCode] || null
}
function state(role, code) {
  if (code == null || !CODES.has(code)) throw new Error('未知权限码: ' + code)
  const r = canonical(role)
  if (r == null) return 'DENY'
  return (STATES[code] && STATES[code][r]) || 'DENY'
}
function allowsNew(role, code) { return state(role, code) === 'Y' }
function canonicalCodes() { return new Set(CODES) }
function canonicalRoles() { return new Set(Object.keys(ROLE)) }
function defaultScope(role) {
  const r = canonical(role)
  return r == null ? null : (SCOPE[r] || null)
}

module.exports = { ROLE, ALIAS, SCOPE, canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope }