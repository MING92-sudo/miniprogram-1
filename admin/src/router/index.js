import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveRoute } from './guard'
import AppShell from '../ui/AppShell.vue'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const NotFoundView = () => import('../views/NotFoundView.vue')
// M3 台账域
const OrgView = () => import('../views/OrgView.vue')
const UsersView = () => import('../views/UsersView.vue')
const RolesView = () => import('../views/RolesView.vue')
const GrantsView = () => import('../views/GrantsView.vue')
const ProjectsView = () => import('../views/ProjectsView.vue')
const ContractsView = () => import('../views/ContractsView.vue')
const ElevatorsView = () => import('../views/ElevatorsView.vue')
// M4 作业域
const HandoversView = () => import('../views/HandoversView.vue')
const PlansView = () => import('../views/PlansView.vue')
const RecordsView = () => import('../views/RecordsView.vue')
const DispatchView = () => import('../views/DispatchView.vue')
const OrderDetailView = () => import('../views/OrderDetailView.vue')
// M5 数据域
const StatsView = () => import('../views/StatsView.vue')
const AuditView = () => import('../views/AuditView.vue')
const SettingsView = () => import('../views/SettingsView.vue')
// 监管与档案（M5 迁至自建组件）
const FailedRecordsView = () => import('../views/FailedRecordsView.vue')
const UploadLogsView = () => import('../views/UploadLogsView.vue')
const FilingView = () => import('../views/FilingView.vue')
const RegionView = () => import('../views/RegionView.vue')
const TemplateManageView = () => import('../views/TemplateManageView.vue')
const RegisterWorkersView = () => import('../views/RegisterWorkersView.vue')
// 二期：监管与运营（原型同款 mock 演示，后端接口后续接入）
const SyncView = () => import('../views/SyncView.vue')
const MsgTemplatesView = () => import('../views/MsgTemplatesView.vue')
const ReviewsView = () => import('../views/ReviewsView.vue')
const BoardView = () => import('../views/BoardView.vue')

export const routes = [
  { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/', component: AppShell, redirect: '/dashboard', children: [
    { path: 'dashboard', component: DashboardView, meta: { title: '工作台', group: '概览' } },
    { path: 'org', component: OrgView, meta: { title: '组织架构', group: '组织与权限', perm: 'user:view' } },
    { path: 'users', component: UsersView, meta: { title: '账号管理', group: '组织与权限', perm: 'user:view' } },
    { path: 'roles', component: RolesView, meta: { title: '角色权限矩阵', group: '组织与权限', perm: 'role:assign' } },
    { path: 'grants', component: GrantsView, meta: { title: '授权管理', group: '组织与权限', perm: 'invite:manage' } },
    { path: 'projects', component: ProjectsView, meta: { title: '项目台账', group: '台账中心', perm: 'project:view' } },
    { path: 'contracts', component: ContractsView, meta: { title: '合同管理', group: '台账中心', perm: 'contract:view' } },
    { path: 'elevators', component: ElevatorsView, meta: { title: '电梯台账', group: '台账中心', perm: 'elevator:view' } },
    { path: 'handovers', component: HandoversView, meta: { title: '移交管理', group: '安装移交', perm: 'handover:view' } },
    { path: 'plans', component: PlansView, meta: { title: '维保计划', group: '维保作业', perm: 'plan:view' } },
    { path: 'records', component: RecordsView, meta: { title: '记录单复核', group: '维保作业', perm: 'record:view' } },
    { path: 'dispatch', component: DispatchView, meta: { title: '工单调度', group: '急修报修', perm: 'order:view_pool' } },
    { path: 'order/:id', component: OrderDetailView, meta: { title: '工单详情', group: '急修报修', perm: 'order:view_own' } },
    { path: 'stats', component: StatsView, meta: { title: '统计报表', group: '数据与系统', perm: 'stats:view' } },
    { path: 'audit', component: AuditView, meta: { title: '审计日志', group: '数据与系统', perm: 'audit:view' } },
    { path: 'settings', component: SettingsView, meta: { title: '系统配置', group: '数据与系统', perm: 'config:manage' } },
    { path: 'reports/failed', component: FailedRecordsView, meta: { title: '上报异常清单', group: '监管与档案', perm: 'report:view' } },
    { path: 'reports/logs', component: UploadLogsView, meta: { title: '上报日志', group: '监管与档案', perm: 'report:view' } },
    { path: 'filings', component: FilingView, meta: { title: '外接建档', group: '监管与档案', perm: 'filing:view' } },
    { path: 'regions', component: RegionView, meta: { title: '区域管理', group: '监管与档案', perm: 'region:view' } },
    { path: 'templates', component: TemplateManageView, meta: { title: '检查项模板', group: '监管与档案', perm: 'template:view' } },
    { path: 'register/workers', component: RegisterWorkersView, meta: { title: '平台登记人员', group: '监管与档案', perm: 'platform:worker_view' } },
    { path: 'sync', component: SyncView, meta: { title: '监管平台同步', group: '监管与运营', perm: 'report:view' } },
    { path: 'msg-templates', component: MsgTemplatesView, meta: { title: '消息模板', group: '监管与运营', perm: 'config:manage' } },
    { path: 'reviews', component: ReviewsView, meta: { title: '服务评价', group: '监管与运营', perm: 'stats:view' } }
  ] },
  // 大屏调度：独立全屏（原型 Views.board 不渲染侧栏/顶栏）
  { path: '/board', component: BoardView, meta: { title: '大屏调度', group: '监管与运营', perm: 'order:view_pool' } },
  { path: '/403', component: ForbiddenView, meta: { public: true, title: '无权限' } },
  { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { public: true, title: '页面不存在' } }
]
// 开发态工具路由（D-A3，仅 DEV，不进生产导航）
if (import.meta.env.DEV) {
  routes.splice(2, 0,
    { path: '/dev/components', component: () => import('../views/dev/ComponentsGallery.vue'), meta: { public: true, title: '组件廊' } },
    { path: '/dev/spec', component: () => import('../views/dev/DataSelfCheck.vue'), meta: { public: true, title: '数据自检' } }
  )
}
const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const auth = useAuthStore()
  return resolveRoute(to, { token: auth.token, role: auth.role })
})
router.afterEach((to) => {
  document.title = to.meta && to.meta.title ? `${to.meta.title} · 博威无纸化管理端` : '博威无纸化管理端'
})
export default router