// 前端仅体验优化，后端唯一权威；
const ROLE = {
  PLATFORM_ADMIN: 'PLATFORM_ADMIN', INST_MGR: 'INST_MGR',
  INST_PM: 'INST_PM', INST_CLERK: 'INST_CLERK', INSTALLER: 'INSTALLER',
  MAINT_MGR: 'MAINT_MGR', MAINT_SUP: 'MAINT_SUP', MAINT_TECH: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY',
  CLIENT_ADMIN: 'CLIENT_ADMIN', CLIENT_SIGNER: 'CLIENT_SIGNER'
}
const ALIAS = {
  ADMIN: 'PLATFORM_ADMIN', SYS_ADMIN: 'PLATFORM_ADMIN', INSTALL_MANAGER: 'INST_PM', // 2026-11-21：SYS_ADMIN 已并为 PLATFORM_ADMIN 别名
  INSTALL_CLERK: 'INST_CLERK', INSTALL_WORKER: 'INSTALLER', MAINT_MANAGER: 'MAINT_MGR',
  LEADER: 'MAINT_SUP', WORKER: 'MAINT_TECH', ASSISTANT: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY', UNIT_ADMIN: 'CLIENT_ADMIN'
}
const SCOPE = {
  PLATFORM_ADMIN: 'ALL', INST_MGR: 'ORG', INST_PM: 'PROJECT',
  INST_CLERK: 'PROJECT', INSTALLER: 'SELF', MAINT_MGR: 'ALL', MAINT_QUALITY: 'ORG',
  MAINT_BIZ: 'ORG', MAINT_SUP: 'TEAM', MAINT_TECH: 'SELF', CLIENT_ADMIN: 'ORG_FLAT',
  CLIENT_SIGNER: 'ORG_FLAT'
}
const PA = 'PLATFORM_ADMIN', IM = 'INST_MGR', IP = 'INST_PM', IC = 'INST_CLERK',
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
y('handover:view', IM, MS, MM, PA); c('handover:view', IC, IP, IW, MT)
y('handover:create', IP, IC)
y('handover:edit', IP); c('handover:edit', IC)
y('handover:doc_upload', IC, IP)
y('handover:doc_delete', IC, IP)
y('handover:submit', IP)
y('handover:review', IM)
y('handover:review_return', IM)
y('handover:receive', MS)
y('handover:inspect', IW, MT)
y('handover:rectify', IP, IC, IW)
y('handover:sign_install', IM, IW)
y('handover:sign_maint', MS, MM)
y('handover:void', IM, MM, PA)
y('handover:export', IM, MS, MM, PA)
y('handover:notify_client', MS)
// §4.2 项目合同电梯（9）
y('project:view', MS, MM, PA); c('project:view', IC, IP, IM)
y('project:edit', MM, PA)
y('contract:view', MM, PA, MB); c('contract:view', MS)
y('contract:edit', MM, PA, MB)
c('elevator:view', PA, IM, IP, IC, IW, MM, MS, MT, MB, MQ)
y('elevator:edit', MS, PA)
y('elevator:qr', MT, MS)
y('elevator:transfer', MM, PA)
y('elevator:export', MM, PA); c('elevator:export', MS)
// §4.3 维保作业（15）
y('plan:view', MS, MM); c('plan:view', MT)
y('plan:generate', MS, MM)
y('task:accept', MT)
y('task:assign_partner', MS, MT)
y('checklist:fill', MT)
y('record:view', MS, MM, PA); c('record:view', MT)
y('record:submit', MT)
y('record:sign_tech', MT)
y('record:review', MS, MM)
y('record:sign_request', MT, MS)
y('record:sign') // P1 T2：客户签署收敛到 client:sign；record:sign 仅平台管理员持有
y('record:refuse') // P1 T2：拒签能力 P3 增 client:refuse 前仅平台管理员持有（DC3）
y('record:archive', MS)
y('record:amend', MM, PA)
y('record:export', MS, MM, PA)
y('order:create')
y('order:create_proxy', MS)
y('order:view_pool', MS, MM); c('order:view_pool', MT)
y('order:view_own') // P1 T2：仅平台管理员持有（客户工单读走锚点判定，不依赖本码）
y('order:grab', MT)
y('order:dispatch', MS, MM)
c('order:arrive', MT)
c('order:process', MT)
y('order:submit', MT)
y('order:confirm') // 客户确认使用 client:sign
y('order:close', MS)
y('order:cancel', MS)
y('order:escalate', MM)
y('order:export', MS, MM, PA)
// evaluate/user_manage 默认关闭，invite 仅 MS。
y('client:invite', MS)
y('client:user_manage')
y('client:elevator_view', CA, CS)
y('client:report', CA, CS)
y('client:sign', CA, CS)
y('client:evaluate')
y('client:download', CA, CS)
// §4.6 组织权限审计（10）
y('user:view', PA); c('user:view', IM, MS, MM)
y('user:create', IM, MS, MM, PA)
y('user:disable', IM, MS, MM, PA)
y('role:assign', PA)
y('invite:manage', IM, MS, MM, PA)
y('stats:view', IM, MS, MM, PA)
y('stats:export', MM, PA); c('stats:export', IM, MS)
y('audit:view', PA)
y('config:manage', PA)
y('data:export_sensitive', PA)
y('report:view', PA, MM); c('report:view', MS, MQ)
y('report:retry', PA, MM)
y('region:view', PA)
y('region:write', PA)
y('template:view', PA, MM); c('template:view', MQ)
y('template:write', PA, MM, MQ)
y('template:toggle', PA, MQ)
y('platform:worker_view', PA); c('platform:worker_view', IP, IC, MM, MS)
y('platform:worker_register', PA, MM)
y('platform:sync', PA)
y('record:spotcheck', MQ)
y('client:edit', PA, MB)
y('user:reset_password', PA, MM, IM)

Object.keys(STATES).forEach((code) => { STATES[code][PA] = 'Y' })

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