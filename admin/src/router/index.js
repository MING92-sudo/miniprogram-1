import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveRoute } from './guard'

import AdminLayout from '../layout/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import OrderListView from '../views/OrderListView.vue'
import OrderDetailView from '../views/OrderDetailView.vue'
import FailedRecordsView from '../views/FailedRecordsView.vue'
import UploadLogsView from '../views/UploadLogsView.vue'
import SyncStatusView from '../views/SyncStatusView.vue'
import CompanyView from '../views/CompanyView.vue'
import UseUnitsView from '../views/UseUnitsView.vue'
import EmployeesView from '../views/EmployeesView.vue'
import ElevatorsView from '../views/ElevatorsView.vue'
import InspectsView from '../views/InspectsView.vue'
import DrillsView from '../views/DrillsView.vue'
import RescuesView from '../views/RescuesView.vue'
import FaultsView from '../views/FaultsView.vue'
import StatsView from '../views/StatsView.vue'
import ScheduleView from '../views/ScheduleView.vue'
import TemplateManageView from '../views/TemplateManageView.vue'
import AlertRulesView from '../views/AlertRulesView.vue'
import NotifyRecordsView from '../views/NotifyRecordsView.vue'
import UsersView from '../views/UsersView.vue'
import OpLogsView from '../views/OpLogsView.vue'
import ApprovalsView from '../views/ApprovalsView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'
import NotFoundView from '../views/NotFoundView.vue'

// 路由表；hash 模式便于静态托管部署
export const routes = [
  { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: DashboardView, meta: { title: '监控看板' } },
      { path: 'orders', component: OrderListView, meta: { title: '工单列表' } },
      { path: 'orders/:id', component: OrderDetailView, meta: { title: '工单详情' } },
      { path: 'reports/failed', component: FailedRecordsView, meta: { title: '上报异常清单' } },
      { path: 'reports/logs', component: UploadLogsView, meta: { title: '上报日志' } },
      { path: 'approvals', component: ApprovalsView, meta: { title: '定位异常申述审核' } },
      { path: 'platform/sync', component: SyncStatusView, meta: { title: '平台同步' } },
      { path: 'schedule', component: ScheduleView, meta: { title: '计划调度' } },
      { path: 'templates', component: TemplateManageView, meta: { title: '检查项模板' } },
      { path: 'archive/company', component: CompanyView, meta: { title: '维保单位档案' } },
      { path: 'archive/use-units', component: UseUnitsView, meta: { title: '使用单位档案' } },
      { path: 'archive/employees', component: EmployeesView, meta: { title: '人员档案' } },
      { path: 'archive/elevators', component: ElevatorsView, meta: { title: '电梯档案' } },
      { path: 'ledger/inspects', component: InspectsView, meta: { title: '自行检查台账' } },
      { path: 'ledger/drills', component: DrillsView, meta: { title: '应急演练台账' } },
      { path: 'ledger/rescues', component: RescuesView, meta: { title: '救援台账' } },
      { path: 'ledger/faults', component: FaultsView, meta: { title: '故障台账' } },
      { path: 'stats', component: StatsView, meta: { title: '统计报表' } },
      { path: 'alerts', component: AlertRulesView, meta: { title: '预警规则' } },
      { path: 'notify', component: NotifyRecordsView, meta: { title: '发送记录' } },
      { path: 'users', component: UsersView, meta: { title: '用户权限', role: 'SYS_ADMIN' } },
      { path: 'op-logs', component: OpLogsView, meta: { title: '审计日志', role: 'SYS_ADMIN' } }
    ]
  },
  { path: '/403', component: ForbiddenView, meta: { public: true, title: '无权限' } },
  { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { public: true, title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  return resolveRoute(to, { token: auth.token, role: auth.role })
})

router.afterEach((to) => {
  document.title = to.meta && to.meta.title
    ? `${to.meta.title} · 电梯维保管理端`
    : '电梯维保管理端'
})

export default router
