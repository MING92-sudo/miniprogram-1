// 新 IA 菜单常量（原型 GROUP_ORDER + NAV_VIEWS 对齐）；渐进渲染：仅 implemented 视图显示。
// perm=null 表示无条件可见（工作台落地页，实际访问由路由守卫/后端判）。
// 二期 4 视图权限码映射（偏离登记）：sync:view→report:view、msg:manage→config:manage、review:view→stats:view、board:view→order:view_pool。
import { state } from './permission-v2'
export const MENU_GROUPS = [
  { title: '概览', items: [{ path: '/dashboard', title: '概览工作台', perm: null, icon: 'gauge' }] },
  { title: '组织与权限', items: [
    { path: '/org', title: '组织架构', perm: 'user:view', icon: 'sitemap' },
    { path: '/use-units', title: '使用单位档案', perm: 'client:edit', icon: 'building' },
    { path: '/users', title: '账号管理', perm: 'user:view', icon: 'users' },
    { path: '/roles', title: '角色权限矩阵', perm: 'role:assign', icon: 'shield' },
    { path: '/grants', title: '授权管理', perm: 'invite:manage', icon: 'key' }
  ] },
  { title: '台账中心', items: [
    { path: '/projects', title: '项目台账', perm: 'project:view', icon: 'folder' },
    { path: '/contracts', title: '合同管理', perm: 'contract:view', icon: 'file-sign' },
    { path: '/elevators', title: '电梯台账', perm: 'elevator:view', icon: 'elevator' }
  ] },
  { title: '安装移交', items: [{ path: '/handovers', title: '移交管理', perm: 'handover:view', icon: 'exchange' }] },
  { title: '维保作业', items: [
    { path: '/plans', title: '维保计划', perm: 'plan:view', icon: 'calendar' },
    { path: '/records', title: '记录单复核', perm: 'record:view', icon: 'clipboard' }
  ] },
  { title: '急修报修', items: [
    { path: '/dispatch', title: '工单调度', perm: 'order:view_pool', icon: 'bolt' }
  ] },
  { title: '数据与系统', items: [
    { path: '/stats', title: '统计报表', perm: 'stats:view', icon: 'chart' },
    { path: '/audit', title: '审计日志', perm: 'audit:view', icon: 'history' },
    { path: '/settings', title: '系统配置', perm: 'config:manage', icon: 'sliders' }
  ] },
  { title: '监管与档案', items: [
    { path: '/reports/failed', title: '上报异常清单', perm: 'report:view', icon: 'alert' },
    { path: '/reports/logs', title: '上报日志', perm: 'report:view', icon: 'file-text' },
    { path: '/filings', title: '外接建档', perm: 'filing:view', icon: 'folder' },
    { path: '/regions', title: '区域管理', perm: 'region:view', icon: 'map-pin' },
    { path: '/templates', title: '检查项模板', perm: 'template:view', icon: 'clipboard-list' },
    { path: '/register/workers', title: '平台登记人员', perm: 'platform:worker_view', icon: 'user-check' }
  ] },
  { title: '监管与运营', items: [
    { path: '/sync', title: '监管平台同步', perm: 'report:view', icon: 'cloud-upload' },
    { path: '/msg-templates', title: '消息模板', perm: 'config:manage', icon: 'message' },
    { path: '/reviews', title: '服务评价', perm: 'stats:view', icon: 'star' },
    { path: '/board', title: '大屏调度', perm: 'order:view_pool', icon: 'monitor' }
  ] }
]
// 已落地视图白名单：AppShell 用它过滤「配置了菜单但页面还没做」的项。
// 新增页面必须同步登记，否则菜单里根本不出现（回归守卫见 menu.test.js）。
export const IMPLEMENTED_PATHS = ['/dashboard', '/org', '/use-units', '/users', '/roles', '/grants',
  '/projects', '/contracts', '/elevators', '/handovers', '/plans', '/records', '/dispatch', '/order',
  '/stats', '/audit', '/settings', '/reports/failed', '/reports/logs', '/filings', '/regions',
  '/templates', '/register/workers', '/sync', '/msg-templates', '/reviews', '/board']

function visible(i, role) { return i.perm == null || state(role, i.perm) !== 'DENY' }
export function visibleMenus(role, implementedPaths) {
  const impl = new Set(implementedPaths)
  return MENU_GROUPS.map(g => ({
    ...g, items: g.items.filter(i => impl.has(i.path) && visible(i, role))
  })).filter(g => g.items.length)
}