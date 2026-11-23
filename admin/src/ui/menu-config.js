import { stateForRoles } from './permission-v2'
export const MENU_GROUPS = [
  { title: '概览', items: [{ path: '/dashboard', title: '概览工作台', perm: null, icon: 'gauge' }] },
  { title: '电梯管理', items: [
    { path: '/elevators', title: '电梯台账', perm: 'elevator:view', icon: 'elevator' }
  ] },
  { title: '项目管理', items: [
    { path: '/projects', title: '项目台账', perm: 'project:view', icon: 'folder' }
  ] },
  { title: '维保管理', items: [
    { path: '/dispatch', title: '工单调度', perm: 'order:view_pool', icon: 'bolt' },
    { path: '/records', title: '记录单复核', perm: 'record:view', icon: 'clipboard' },
    { path: '/handovers', title: '移交管理', perm: 'handover:view', icon: 'exchange' },
    { path: '/plans', title: '维保计划', perm: 'plan:view', icon: 'calendar' }
  ] },
  { title: '公司管理', items: [
    { path: '/users', title: '账号管理', perm: 'user:view', icon: 'users' },
    { path: '/roles', title: '角色权限', perm: 'role:assign', icon: 'shield' },
    { path: '/use-units', title: '使用单位档案', perm: 'client:edit', icon: 'building' },
    { path: '/org', title: '组织架构', perm: 'user:view', icon: 'sitemap' }
  ] },
  { title: '数据对接', items: [
    { path: '/reports/failed', title: '上报异常清单', perm: 'report:view', icon: 'alert' },
    { path: '/reports/logs', title: '上报日志', perm: 'report:view', icon: 'file-text' },
    { path: '/register/workers', title: '平台登记人员', perm: 'platform:worker_view', icon: 'user-check' },
    { path: '/sync', title: '监管平台同步', perm: 'report:view', icon: 'cloud-upload' }
  ] },
  { title: '系统', items: [
    { path: '/stats', title: '统计报表', perm: 'stats:view', icon: 'chart' },
    { path: '/audit', title: '审计日志', perm: 'audit:view', icon: 'history' },
    { path: '/settings', title: '系统配置', perm: 'config:manage', icon: 'sliders' }
  ] }
]
// 新增页面必须同步登记，否则菜单里根本不出现（回归守卫见 menu.test.js）。
export const IMPLEMENTED_PATHS = ['/dashboard', '/elevators', '/projects', '/dispatch', '/records',
  '/handovers', '/plans', '/users', '/roles', '/use-units', '/org',
  '/reports/failed', '/reports/logs', '/register/workers', '/sync',
  '/stats', '/audit', '/settings',
  '/contracts', '/grants', '/regions', '/templates',
  '/order', '/elevator', '/migration-issues', '/msg-templates', '/reviews', '/board']

function visible(i, roles) { return i.perm == null || stateForRoles(roles, i.perm) !== 'DENY' }
export function visibleMenus(roleOrRoles, implementedPaths) {
  const roles = Array.isArray(roleOrRoles) ? roleOrRoles : [roleOrRoles]
  const impl = new Set(implementedPaths)
  return MENU_GROUPS.map(g => ({
    ...g, items: g.items.filter(i => impl.has(i.path) && visible(i, roles))
  })).filter(g => g.items.length)
}
