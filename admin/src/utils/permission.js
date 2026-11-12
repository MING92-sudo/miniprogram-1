/**
 * 管理端角色能力矩阵（唯一数据来源：docs/14 §3 角色码表 + §6 管理端权限矩阵）。
 *
 * 定位：前端隐藏/置灰只是体验优化，**后端 `auth/PermissionMatrix.java` 才是权威**；
 * 越权写一律在后端判 `403`/`1403` 且不得有副作用（docs/14 §8 R9、§12.2）。
 * 能力码字符串必须与后端 `PermissionMatrix` 逐字一致（拼错即静默放行，等同越权），
 * 后端 `/auth/menus` 接口落地后本表改由接口下发，前端仅保留兜底常量。
 *
 * 口径：矩阵中 `W` = 写（含读）、`R` = 只读、`—` = 不可达。
 * 因此本表只声明 `X:write`，读能力由 `expand()` 自动补齐 `X:read`（write ⇒ read）。
 * 无对应写操作的能力码（`report:retry`、`account:manage`）单独声明，不参与补齐。
 */

/** 能力码（与 backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java 同名） */
export const CAP = {
  CONSOLE_READ: 'console:read',
  CONSOLE_WRITE: 'console:write',
  ORDER_READ: 'order:read',
  ORDER_WRITE: 'order:write',
  FAULT_READ: 'fault:read',
  FAULT_WRITE: 'fault:write',
  RECORD_READ: 'record:read',
  REPORT_RETRY: 'report:retry',
  PLAN_WRITE: 'plan:write',
  TEMPLATE_WRITE: 'template:write',
  LEDGER_READ: 'ledger:read',
  LEDGER_WRITE: 'ledger:write',
  EMPLOYEE_READ: 'employee:read',
  EMPLOYEE_WRITE: 'employee:write',
  CONTRACT_READ: 'contract:read',
  CONTRACT_WRITE: 'contract:write',
  USEUNIT_READ: 'useunit:read',
  USEUNIT_WRITE: 'useunit:write',
  HANDOVER_READ: 'handover:read',
  HANDOVER_WRITE: 'handover:write',
  FILING_READ: 'filing:read',
  FILING_WRITE: 'filing:write',
  REGION_WRITE: 'region:write',
  ACCOUNT_MANAGE: 'account:manage'
}

/** 角色码（docs/14 §3；码值沿用原则：LEADER/WORKER/UNIT_ADMIN 不换码值） */
export const ROLE = {
  SYS_ADMIN: 'SYS_ADMIN',
  ADMIN: 'ADMIN',
  INSTALL_MANAGER: 'INSTALL_MANAGER',
  INSTALL_CLERK: 'INSTALL_CLERK',
  INSTALL_WORKER: 'INSTALL_WORKER',
  MAINT_MANAGER: 'MAINT_MANAGER',
  MAINT_BIZ: 'MAINT_BIZ',
  LEADER: 'LEADER',
  MAINT_QUALITY: 'MAINT_QUALITY',
  WORKER: 'WORKER',
  ASSISTANT: 'ASSISTANT',
  UNIT_ADMIN: 'UNIT_ADMIN'
}

/**
 * 能力 → 角色（docs/14 §6 逐格照抄，改这里等于改矩阵，必须同步文档与后端）。
 * SYS_ADMIN 按该文档「保留不启用但同权」处理，在下方与 ADMIN 合并。
 *
 * 与已落地的后端 `auth/PermissionMatrix.java` 对齐口径（2026-11-12 核对）：
 * - 8 个可登录管理端的角色（RoleCatalog.adminConsole=true）逐格一致：console:write 收窄为平台层
 *   （后端 P1 明确"保持旧两档门禁"，与现有视图写门禁 auth.canWrite 同口径）；employee:read 不含
 *   MAINT_QUALITY（§6「人员档案」质量负责人为 —）。以上两处按后端收窄，避免前端放出后端会 403 的入口。
 * - handover:write / filing:write 保留 INSTALL_CLERK：§6「安装文员 R（资料 W）」与 §5
 *   「仅 3 类必传资料上传/替换」明确其上传权，后端 P1 矩阵暂缺该角色，待后端补齐（前端按文档放行）。
 * - 后端按 §7 给小程序角色（WORKER/ASSISTANT/UNIT_ADMIN）额外授 order/fault/record 等能力；
 *   管理端表不复制——它们 console:read=false，登录管理端即 403，无管理端入口。
 */
export const CAPABILITY_ROLES = {
  'console:read': ['ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'MAINT_MANAGER', 'MAINT_BIZ', 'LEADER', 'MAINT_QUALITY'],
  'console:write': ['ADMIN', 'SYS_ADMIN'],
  'plan:write': ['ADMIN', 'MAINT_MANAGER', 'LEADER'],
  'ledger:read': ['ADMIN', 'INSTALL_MANAGER', 'MAINT_MANAGER', 'MAINT_BIZ', 'LEADER', 'MAINT_QUALITY'],
  'ledger:write': ['ADMIN', 'MAINT_MANAGER'],
  'template:write': ['ADMIN', 'MAINT_MANAGER', 'MAINT_QUALITY'],
  'order:read': ['ADMIN', 'MAINT_MANAGER', 'LEADER', 'MAINT_QUALITY'],
  'order:write': ['ADMIN', 'MAINT_MANAGER'],
  'fault:read': ['ADMIN', 'MAINT_MANAGER', 'LEADER', 'MAINT_QUALITY'],
  'fault:write': ['ADMIN', 'MAINT_MANAGER'],
  'record:read': ['ADMIN', 'MAINT_MANAGER', 'LEADER', 'MAINT_QUALITY'],
  'report:retry': ['ADMIN', 'MAINT_MANAGER'],
  'handover:read': ['ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'INSTALL_WORKER', 'MAINT_MANAGER', 'MAINT_QUALITY'],
  'handover:write': ['ADMIN', 'SYS_ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'INSTALL_WORKER', 'MAINT_MANAGER'],
  'filing:read': ['ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'MAINT_MANAGER', 'MAINT_QUALITY'],
  'filing:write': ['ADMIN', 'SYS_ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'MAINT_MANAGER'],
  'contract:read': ['ADMIN', 'INSTALL_MANAGER', 'MAINT_MANAGER', 'MAINT_BIZ'],
  'contract:write': ['ADMIN', 'MAINT_BIZ'],
  'useunit:read': ['ADMIN', 'MAINT_MANAGER', 'MAINT_BIZ'],
  'useunit:write': ['ADMIN', 'MAINT_BIZ'],
  'employee:read': ['ADMIN', 'INSTALL_MANAGER', 'INSTALL_CLERK', 'MAINT_MANAGER', 'LEADER'],
  'employee:write': ['ADMIN', 'INSTALL_MANAGER', 'MAINT_MANAGER'],
  'region:write': ['ADMIN'],
  'account:manage': ['ADMIN', 'MAINT_MANAGER']
}

/** 写能力自动补齐的读能力（`X:write` ⇒ `X:read`） */
function expand(caps) {
  const out = new Set(caps)
  caps.forEach((cap) => {
    if (cap.endsWith(':write')) out.add(cap.slice(0, -':write'.length) + ':read')
  })
  return out
}

/** 角色 → 能力集合（由 CAPABILITY_ROLES 反投影，只读使用） */
export const ROLE_PERMS = Object.keys(ROLE).reduce((acc, role) => {
  const caps = Object.keys(CAPABILITY_ROLES).filter((cap) => CAPABILITY_ROLES[cap].includes(role))
  acc[role] = caps
  return acc
}, {})

// SYS_ADMIN 与 ADMIN 同权（docs/14 §3：保留角色，不另立矩阵）
ROLE_PERMS.SYS_ADMIN = [...ROLE_PERMS.ADMIN]

const PERMS_BY_ROLE = Object.keys(ROLE_PERMS).reduce((acc, role) => {
  acc[role] = expand(ROLE_PERMS[role])
  return acc
}, {})

/**
 * 角色是否具备某项能力。未知角色/未知能力一律 false（fail-closed：不因拼错放行）。
 * @param {string} role 角色码
 * @param {string} capability 能力码（`X:write` 隐含有 `X:read`）
 */
export function can(role, capability) {
  if (!role || !capability) return false
  const perms = PERMS_BY_ROLE[role]
  if (!perms) return false
  return perms.has(capability)
}

/** 角色是否具备任一能力（菜单分组、多入口页面用） */
export function canAny(role, capabilities) {
  if (!Array.isArray(capabilities) || capabilities.length === 0) return false
  return capabilities.some((cap) => can(role, cap))
}

/** 角色是否在码表内（登录后角色不存在即视为不可用，页面按 403 处理） */
export function isKnownRole(role) {
  return Boolean(role) && Object.prototype.hasOwnProperty.call(PERMS_BY_ROLE, role)
}
