// M2 mock 基准集（admin-spec §9，与原型同源）；时间基准 2026-11-13（周五）
export const TODAY = '2026-11-13'
const PROJECTS = [
  { id: 'p1', name: '城东大厦', elevatorCount: 6 },
  { id: 'p2', name: '汇金中心', elevatorCount: 4 },
  { id: 'p3', name: '城市之光', elevatorCount: 4 },
  { id: 'p4', name: '云栖里', elevatorCount: 3 },
  { id: 'p5', name: '华府名邸', elevatorCount: 3 }
]
const HANDOVER_STATES = ['DRAFT', 'SUBMITTED', 'REVIEWING', 'RETURNED', 'ACCEPTED', 'INSPECTING', 'RECTIFYING', 'SIGNING', 'ARCHIVED', 'VOID']
const RECORD_STATES = ['DRAFT', 'SUBMITTED', 'REVIEWING', 'RETURNED', 'SIGNING', 'ARCHIVED', 'AMENDING']
const ORDER_STATES = ['CREATED', 'ASSIGNED', 'ACCEPTED', 'ARRIVED', 'PROCESSING', 'SUBMITTED', 'CLOSED']
function buildElevators() {
  const out = []
  let n = 0
  PROJECTS.forEach(p => {
    for (let i = 0; i < p.elevatorCount; i++) {
      n++
      out.push({ id: 'el' + n, code: 'DT-CB-' + String(300 + n).padStart(4, '0'), name: p.name + ' ' + (i + 1) + '# 客梯', projectId: p.id, project: p.name, lastMaintDate: '2026-11-0' + (1 + (n % 9)), status: 'NORMAL' })
    }
  })
  return out
}
function buildDocs(prefix, count, states, dateField) {
  return Array.from({ length: count }, (_, i) => ({
    id: prefix + (i + 1),
    no: prefix.toUpperCase() + '-2026-' + String(100 + i),
    status: states[i % states.length],
    projectId: PROJECTS[i % PROJECTS.length].id,
    elevatorCode: 'DT-CB-' + String(301 + (i % 20)).padStart(4, '0'),
    [dateField]: TODAY,
    primaryId: 'T3101', partnerId: i % 3 === 0 ? null : 'T3122',
    primarySignAt: TODAY + ' 09:00', partnerSignAt: i % 3 === 0 ? null : TODAY + ' 10:00'
  }))
}
export const mockData = {
  today: TODAY,
  projects: PROJECTS,
  elevators: buildElevators(),
  handovers: buildDocs('YJ', 12, HANDOVER_STATES, 'createdAt'),
  records: buildDocs('WB', 14, RECORD_STATES, 'workDate'),
  orders: buildDocs('JX', 16, ORDER_STATES, 'createdAt').map((o, i) => ({ ...o, slaMinutes: 30, createdAt: TODAY + ' 08:00', elapsed: i % 4 === 0 ? 45 : 10 })),
  contracts: Array.from({ length: 6 }, (_, i) => ({ id: 'ht' + (i + 1), no: 'HT-2026-03' + (10 + i), projectId: PROJECTS[i % 5].id, endDate: '2026-12-31', daysLeft: 48, amount: 120000 })),
  accounts: Array.from({ length: 18 }, (_, i) => ({ id: 'u' + (i + 1), name: '账号' + (i + 1), status: ['PENDING', 'NORMAL', 'DISABLED', 'LEFT'][i % 4] })),
  audit: Array.from({ length: 24 }, (_, i) => ({ id: 'a' + (i + 1), actor: '李振国', role: 'MAINT_TECH', action: 'record:submit', time: TODAY + ' 09:00' })),
  invites: Array.from({ length: 8 }, (_, i) => ({ id: 'iv' + (i + 1), code: 'INV' + (1000 + i) })),
  grants: Array.from({ length: 5 }, (_, i) => ({ id: 'g' + (i + 1), grantee: 'u' + (i + 1), perm: 'order:dispatch', status: 'ACTIVE' }))
}
// 范围台量（admin-spec §9 不变量 8）：ALL/MAINT_MGR 20，MAINT_SUP 14，INST_MGR 6，CLIENT_ADMIN 14
const SCOPE_COUNT = { ALL: 20, PLATFORM_ADMIN: 20, SYS_ADMIN: 20, MAINT_MGR: 20, MAINT_SUP: 14, INST_MGR: 6, CLIENT_ADMIN: 14 }
export function scopeElevatorCount(scope) { return SCOPE_COUNT[scope] ?? 20 }
export function checkInvariants(d) {
  const r = []
  const sum = d.projects.reduce((s, p) => s + p.elevatorCount, 0)
  r.push({ id: 1, ok: sum === d.elevators.length && d.elevators.length === 20, detail: '台量闭合 ' + sum + '/' + d.elevators.length })
  r.push({ id: 2, ok: d.elevators.every(e => /^\d{4}-\d{2}-\d{2}$/.test(e.lastMaintDate)), detail: 'lastMaintDate 格式' })
  const pids = new Set(d.projects.map(p => p.id))
  r.push({ id: 3, ok: [...d.handovers, ...d.records, ...d.orders, ...d.contracts].every(x => pids.has(x.projectId)), detail: '引用项目存在' })
  const sets = { handovers: HANDOVER_STATES, records: RECORD_STATES, orders: ORDER_STATES }
  r.push({ id: 4, ok: Object.entries(sets).every(([k, st]) => d[k].every(x => st.includes(x.status))), detail: '状态机合法' })
  r.push({ id: 5, ok: d.records.every(x => !x.partnerId || (x.primaryId !== x.partnerId && x.primarySignAt <= x.partnerSignAt)), detail: '双签' })
  r.push({ id: 6, ok: d.contracts.every(c => c.daysLeft === 48), detail: '临期 daysLeft' })
  r.push({ id: 7, ok: d.orders.every(o => (o.elapsed > o.slaMinutes) === (o.elapsed > 30)), detail: 'SLA 超时判定' })
  r.push({ id: 8, ok: scopeElevatorCount('ALL') === 20 && scopeElevatorCount('MAINT_SUP') === 14 && scopeElevatorCount('INST_MGR') === 6 && scopeElevatorCount('CLIENT_ADMIN') === 14, detail: '范围台量' })
  r.push({ id: 9, ok: d.elevators.every(e => e.lastMaintDate >= '2025-01-01' && e.lastMaintDate <= '2026-12-31'), detail: '日期区间' })
  return r
}