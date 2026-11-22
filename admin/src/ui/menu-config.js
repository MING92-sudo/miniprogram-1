// IA 菜单常量（2026-11-22 精简，对齐 sptecloud 参考：7 组 18 项，合并页用 Tab 承载）。
// perm=null 表示无条件可见（工作台落地页，实际访问由路由守卫/后端判）。
// 合并口径：合同→/projects?tab=contracts；授权→/roles?tab=grants；区域/模板→/settings?tab=…；
// 下线（演示数据/占位，路由保留可直达）：消息模板 /msg-templates、服务评价 /reviews、大屏调度 /board。
import { state } from './permission-v2'
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
// 已落地视图白名单：AppShell 用它过滤「配置了菜单但页面还没做」的项。
// 含被合并/下线但路由仍活的直达路径（/contracts /grants /regions /templates 重定向到合并页）。
// 新增页面必须同步登记，否则菜单里根本不出现（回归守卫见 menu.test.js）。
export const IMPLEMENTED_PATHS = ['/dashboard', '/elevators', '/projects', '/dispatch', '/records',
  '/handovers', '/plans', '/users', '/roles', '/use-units', '/org',
  '/reports/failed', '/reports/logs', '/register/workers', '/sync',
  '/stats', '/audit', '/settings',
  '/contracts', '/grants', '/regions', '/templates',
  '/order', '/elevator', '/migration-issues', '/msg-templates', '/reviews', '/board']

function visible(i, role) { return i.perm == null || state(role, i.perm) !== 'DENY' }
export function visibleMenus(role, implementedPaths) {
  const impl = new Set(implementedPaths)
  return MENU_GROUPS.map(g => ({
    ...g, items: g.items.filter(i => impl.has(i.path) && visible(i, role))
  })).filter(g => g.items.length)
}
