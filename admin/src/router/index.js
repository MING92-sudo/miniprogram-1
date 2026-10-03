import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveRoute } from './guard'

import AdminLayout from '../layout/AdminLayout.vue'

// 路由级代码分包（docs/09 V3.6）：视图按需懒加载，首屏只下载壳 + 当前页，
// 避免 13 个页面与 echarts 全量打进单个 entry chunk（原 2.25 MB / >500 kB 告警）
const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const OrderListView = () => import('../views/OrderListView.vue')
const OrderDetailView = () => import('../views/OrderDetailView.vue')
const FailedRecordsView = () => import('../views/FailedRecordsView.vue')
const UploadLogsView = () => import('../views/UploadLogsView.vue')
const CompanyView = () => import('../views/CompanyView.vue')
const UseUnitsView = () => import('../views/UseUnitsView.vue')
const EmployeesView = () => import('../views/EmployeesView.vue')
const ElevatorsView = () => import('../views/ElevatorsView.vue')
const ScheduleView = () => import('../views/ScheduleView.vue')
const TemplateManageView = () => import('../views/TemplateManageView.vue')
const RegisterRelationsView = () => import('../views/RegisterRelationsView.vue')
const RegisterWorkersView = () => import('../views/RegisterWorkersView.vue')
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const NotFoundView = () => import('../views/NotFoundView.vue')

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
  { path: 'schedule', component: ScheduleView, meta: { title: '计划调度' } },
  { path: 'templates', component: TemplateManageView, meta: { title: '模板管理' } },
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
