// M2 新 IA 菜单常量（8 组，spec §4.3）；渐进渲染：仅 implemented 视图显示。
// perm=null 表示无条件可见（工作台落地页 / 过渡旧版入口，实际访问由路由守卫/后端判）。
import { state } from './permission-v2'
export const MENU_GROUPS = [
  { title: '概览', items: [{ path: '/dashboard', title: '工作台', perm: null, icon: 'dashboard' }] },
  { title: '组织与权限', items: [
    { path: '/org', title: '组织架构', perm: 'user:view', icon: 'org' },
    { path: '/users', title: '账号管理', perm: 'user:view', icon: 'org' },
    { path: '/roles', title: '角色权限矩阵', perm: 'role:assign', icon: 'org' },
    { path: '/grants', title: '授权管理', perm: 'invite:manage', icon: 'org' }
  ] },
  { title: '台账中心', items: [
    { path: '/projects', title: '项目台账', perm: 'project:view', icon: 'ledger' },
    { path: '/contracts', title: '合同管理', perm: 'contract:view', icon: 'ledger' },
    { path: '/elevators', title: '电梯台账', perm: 'elevator:view', icon: 'ledger' }
  ] },
  { title: '安装移交', items: [{ path: '/handovers', title: '移交管理', perm: 'handover:view', icon: 'handover' }] },
  { title: '维保作业', items: [
    { path: '/plans', title: '维保计划', perm: 'plan:view', icon: 'plan' },
    { path: '/records', title: '记录单复核', perm: 'record:view', icon: 'plan' }
  ] },
  { title: '急修报修', items: [
    { path: '/dispatch', title: '工单调度', perm: 'order:view_pool', icon: 'dispatch' },
    { path: '/order', title: '工单详情', perm: 'order:view_own', icon: 'dispatch' }
  ] },
  { title: '数据与系统', items: [
    { path: '/stats', title: '统计报表', perm: 'stats:view', icon: 'stats' },
    { path: '/audit', title: '审计日志', perm: 'audit:view', icon: 'audit' },
    { path: '/settings', title: '系统配置', perm: 'config:manage', icon: 'settings' }
  ] },
  { title: '监管与档案', items: [
    { path: '/reports/failed', title: '上报异常清单', perm: 'report:view', icon: 'compliance' },
    { path: '/reports/logs', title: '上报日志', perm: 'report:view', icon: 'compliance' },
    { path: '/filings', title: '外接建档', perm: 'filing:view', icon: 'compliance' },
    { path: '/regions', title: '区域管理', perm: 'region:view', icon: 'compliance' },
    { path: '/templates', title: '检查项模板', perm: 'template:view', icon: 'compliance' },
    { path: '/register/workers', title: '平台登记人员', perm: 'platform:worker_view', icon: 'compliance' }
  ] }
]
// 过渡组：旧版视图入口（M2 列出，每迁一个删一个；M5 清空）；perm=null 无条件可见，访问由旧路由守卫判
export const LEGACY_GROUP = { title: '旧版（过渡）', legacy: true, items: [
  { path: '/legacy/orders', title: '工单列表（旧）', perm: null },
  { path: '/legacy/ledger', title: '电梯台账（旧）', perm: null },
  { path: '/legacy/schedule', title: '计划调度（旧）', perm: null }
] }
function visible(i, role) { return i.perm == null || state(role, i.perm) !== 'DENY' }
export function visibleMenus(role, implementedPaths) {
  const impl = new Set(implementedPaths)
  const groups = MENU_GROUPS.map(g => ({
    ...g, items: g.items.filter(i => impl.has(i.path) && visible(i, role))
  })).filter(g => g.items.length)
  const legacy = { ...LEGACY_GROUP, items: LEGACY_GROUP.items.filter(i => visible(i, role)) }
  if (legacy.items.length) groups.push(legacy)
  return groups
}