import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveRoute } from './guard'
import AdminLayout from '../layout/AdminLayout.vue'
import AppShell from '../ui/AppShell.vue'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const NotFoundView = () => import('../views/NotFoundView.vue')
// 旧 EP 视图（M2 保活，M3–M5 逐迁）
const OrderListView = () => import('../views/OrderListView.vue')
const OrderDetailView = () => import('../views/OrderDetailView.vue')
const FailedRecordsView = () => import('../views/FailedRecordsView.vue')
const FaultsView = () => import('../views/FaultsView.vue')
const UploadLogsView = () => import('../views/UploadLogsView.vue')
const CompanyView = () => import('../views/CompanyView.vue')
const UseUnitsView = () => import('../views/UseUnitsView.vue')
const EmployeesView = () => import('../views/EmployeesView.vue')
const ElevatorsView = () => import('../views/ElevatorsView.vue')
const ScheduleView = () => import('../views/ScheduleView.vue')
const TemplateManageView = () => import('../views/TemplateManageView.vue')
const LedgerView = () => import('../views/LedgerView.vue')
const RegisterWorkersView = () => import('../views/RegisterWorkersView.vue')
const HandoverView = () => import('../views/HandoverView.vue')
const FilingView = () => import('../views/FilingView.vue')
const ContractView = () => import('../views/ContractView.vue')
const RegionView = () => import('../views/RegionView.vue')

export const routes = [
  { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/', component: AppShell, redirect: '/dashboard', children: [
    { path: 'dashboard', component: DashboardView, meta: { title: '工作台', group: '概览', perm: 'console:read' } }
  ] },
  // 旧管理端保活分支（M5 迁完删除）；路径前缀 /legacy，旧组件零改动
  { path: '/legacy', component: AdminLayout, redirect: '/legacy/orders', children: [
    { path: 'orders', component: OrderListView, meta: { title: '工单列表', perm: 'order:read' } },
    { path: 'orders/:id', component: OrderDetailView, meta: { title: '工单详情', perm: 'order:read' } },
    { path: 'reports/failed', component: FailedRecordsView, meta: { title: '上报异常清单', perm: 'order:read' } },
    { path: 'faults', component: FaultsView, meta: { title: '急修单', perm: 'fault:read' } },
    { path: 'reports/logs', component: UploadLogsView, meta: { title: '上报日志', perm: 'record:read' } },
    { path: 'schedule', component: ScheduleView, meta: { title: '计划调度', perm: 'plan:write' } },
    { path: 'templates', component: TemplateManageView, meta: { title: '模板管理', perm: 'template:write' } },
    { path: 'ledger', component: LedgerView, meta: { title: '电梯台账', perm: 'ledger:read' } },
    { path: 'handovers', component: HandoverView, meta: { title: '移交单', perm: 'handover:read' } },
    { path: 'filings', component: FilingView, meta: { title: '外接建档', perm: 'filing:read' } },
    { path: 'contracts', component: ContractView, meta: { title: '维保合同', perm: 'contract:read' } },
    { path: 'regions', component: RegionView, meta: { title: '区域管理', perm: 'region:write' } },
    { path: 'archive/company', component: CompanyView, meta: { title: '维保单位档案', perm: 'employee:read' } },
    { path: 'archive/use-units', component: UseUnitsView, meta: { title: '使用单位档案', perm: 'useunit:read' } },
    { path: 'archive/employees', component: EmployeesView, meta: { title: '人员档案', perm: 'employee:read' } },
    { path: 'archive/elevators', component: ElevatorsView, meta: { title: '电梯档案', perm: 'ledger:read' } },
    { path: 'register/workers', component: RegisterWorkersView, meta: { title: '人员管理', perm: 'employee:read' } }
  ] },
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
  document.title = to.meta && to.meta.title ? `${to.meta.title} · 梯维通管理端` : '梯维通管理端'
})
export default router