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
import ScheduleView from '../views/ScheduleView.vue'
import RegisterRelationsView from '../views/RegisterRelationsView.vue'
import RegisterWorkersView from '../views/RegisterWorkersView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'
import NotFoundView from '../views/NotFoundView.vue'

// 路由表（docs/09 §四 页面清单）；hash 模式便于静态托管部署
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
      { path: 'platform/sync', component: SyncStatusView, meta: { title: '平台同步' } },
      { path: 'schedule', component: ScheduleView, meta: { title: '计划调度' } },
      { path: 'archive/company', component: CompanyView, meta: { title: '维保单位档案' } },
      { path: 'archive/use-units', component: UseUnitsView, meta: { title: '使用单位档案' } },
      { path: 'archive/employees', component: EmployeesView, meta: { title: '人员档案' } },
      { path: 'archive/elevators', component: ElevatorsView, meta: { title: '电梯档案' } },
      { path: 'register/relations', component: RegisterRelationsView, meta: { title: '服务关系维护（2.3）' } },
      { path: 'register/workers', component: RegisterWorkersView, meta: { title: '人员管理（2.4）' } }
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
  return resolveRoute(to, { token: auth.token })
})

router.afterEach((to) => {
  document.title = to.meta && to.meta.title
    ? `${to.meta.title} · 电梯维保管理端`
    : '电梯维保管理端'
})

export default router
