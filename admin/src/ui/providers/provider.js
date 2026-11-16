// 数据源接口：概览工作台 / 壳层角标用原型 mock 基准集（prototype-mock，与原型同源）；
// 真实业务视图走 api/*，本 provider 只服务演示口径（spec §9 不变量闭合）。
import { M } from '../prototype-mock'
// 旧口径兼容：scope 'ALL'/'SYS_ADMIN' 归一到平台管理员视角
function asRole(role) { return (role === 'ALL' || role === 'SYS_ADMIN') ? 'PLATFORM_ADMIN' : role }
export const provider = {
  getKPIs(role) {
    const k = M.computeKPIs(asRole(role))
    const sw = M.switchByRole(asRole(role))
    const scope = sw ? sw.scope : 'ALL'
    return [
      { key: 'projects', label: '在管项目', value: k.projects, icon: 'folder', unit: '个', sub: '范围 <b>' + scope + '</b>', cls: '', view: '/projects' },
      { key: 'elevators', label: '在管电梯', value: k.elevators, icon: 'elevator', unit: '台', sub: '分项之和 = 总数', cls: 'k-ok', view: '/elevators' },
      { key: 'handoverReview', label: '待审核移交', value: k.handoverPendingReview, icon: 'exchange', unit: '单', sub: '安装部审核', cls: k.handoverPendingReview ? '' : 'k-ok', view: '/handovers' },
      { key: 'recordReview', label: '待复核记录', value: k.recordPendingReview, icon: 'clipboard', unit: '单', sub: '维保主管复核', cls: k.recordPendingReview ? '' : 'k-ok', view: '/records' },
      { key: 'slaOverdue', label: 'SLA 超时候车', value: k.orderOvertime, icon: 'bolt', unit: '单', sub: '需升级 / 改派', cls: k.orderOvertime ? 'k-sig' : 'k-ok', view: '/dispatch' },
      { key: 'contractExpiring', label: '合同临期 ≤90天', value: k.contractExpiring, icon: 'file-sign', unit: '份', sub: '续签提醒', cls: k.contractExpiring ? '' : 'k-ok', view: '/contracts' },
      { key: 'dualSignPending', label: '双签待补', value: k.dualSignPending, icon: 'signature', unit: '单', sub: '配合人未签', cls: k.dualSignPending ? '' : 'k-ok', view: '/records' }
    ]
  },
  getTodos(role) {
    const sw = M.switchByRole(asRole(role))
    const pids = sw ? sw.projectIds : M.PROJECTS.map(p => p.id)
    const inScope = pid => pids.includes(pid)
    const t = []
    M.HANDOVERS.forEach(h => {
      if (!inScope(h.projectId)) return
      if (h.status === '待安装部审核') t.push({ id: h.no, label: '移交内部审核', obj: h.no, perm: 'handover:review', link: '/handovers' })
      if (h.status === '待维保接收') t.push({ id: h.no + '-recv', label: '接收并指派验收', obj: h.no, perm: 'handover:receive', link: '/handovers' })
    })
    M.RECORDS.forEach(r => {
      if (!inScope(r.projectId)) return
      if (r.status === '待复核') t.push({ id: r.no, label: '记录单复核', obj: r.no, perm: 'record:review', link: '/records' })
      if (r.amendPending) t.push({ id: r.no + '-amend', label: '归档修订 · 平台确认', obj: r.no, perm: 'record:amend', link: '/records' })
    })
    M.ORDERS.forEach(o => {
      if (!inScope(o.projectId)) return
      if (o.status === '待接单') t.push({ id: o.no, label: '工单派单', obj: o.no, perm: 'order:dispatch', link: '/dispatch' })
    })
    return t
  },
  getTableRows(domain) {
    const map = { projects: M.PROJECTS, elevators: M.ELEVATORS, handovers: M.HANDOVERS, records: M.RECORDS, orders: M.ORDERS, contracts: M.CONTRACTS, accounts: M.ACCOUNTS, audit: M.AUDIT, invites: M.INVITES, grants: M.GRANTS, plans: M.PLANS }
    return map[domain] || []
  }
}