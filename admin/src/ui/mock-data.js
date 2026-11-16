// 兼容层：旧 M2 mock 基准集出口 → 原型完整基准集（prototype-mock.js，唯一数据源）。
// 旧视图/测试经此取数；新代码请直接 import { M } from './prototype-mock'。
import { M, TODAY } from './prototype-mock'
export { TODAY }
function withId(rows, key) { return rows.map((r, i) => ({ id: r[key] || r.no || r.code || String(i + 1), ...r })) }
export const mockData = {
  today: TODAY,
  projects: M.PROJECTS,
  elevators: M.ELEVATORS,
  handovers: withId(M.HANDOVERS, 'no'),
  records: withId(M.RECORDS, 'no'),
  orders: withId(M.ORDERS, 'no'),
  contracts: M.CONTRACTS,
  accounts: M.ACCOUNTS,
  audit: M.AUDIT.map((a, i) => ({ id: 'a' + (i + 1), ...a })),
  invites: withId(M.INVITES, 'code'),
  grants: M.GRANTS,
  plans: withId(M.PLANS, 'no')
}
// 范围台量（admin-spec §9 不变量 8）：ALL/MAINT_MGR 20，MAINT_SUP 14，INST_MGR 6，CLIENT_ADMIN 14
export function scopeElevatorCount(scope) {
  const role = scope === 'ALL' ? 'PLATFORM_ADMIN' : scope
  return M.scopeElevators(role).length || (scope === 'ALL' || scope === 'SYS_ADMIN' ? 20 : 0)
}
export function checkInvariants() {
  return M.selfCheck().map(c => ({ id: c.id, ok: c.pass, name: c.name, detail: c.detail }))
}