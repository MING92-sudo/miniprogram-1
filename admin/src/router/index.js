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
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const NotFoundView = () => import('../views/NotFoundView.vue')

/**
 * 路由表（docs/09 §四 页面清单）；hash 模式便于静态托管部署。
 *
 * `meta.perm` = 页面所需能力码（docs/14 §6 管理端权限矩阵，码值与后端 PermissionMatrix 同名）。
 * 守卫只做"进不进得去"的门禁，接口层的部门域/归属校验仍由后端判（docs/14 §8 R1/R10）。
 * 矩阵未单列的行按语义就近复用既有能力码，不新增码值（新增即与后端矩阵脱钩）：
 * - 维保单位档案 `/archive/company`：本组织法人主体 + 维保经理联络信息，属"组织/人员主数据"，
 *   矩阵无对应行，就近复用 `employee:read`（写操作仍由后端 `console:write` 侧校验）。
 * - 上报异常清单 `/reports/failed`：矩阵"上报异常清单（重报）"= ADMIN/维保经理 W、质量 R、
 *   班组长 —；现有码值中无 `report:read`，就近复用 `order:read`（含质量，班组长略宽），
 *   重报动作本身由后端 `report:retry` 与前端按钮另行收紧（AGENTS §2.4 禁止批量重试）。
 * - 上报日志 `/reports/logs`：矩阵 = ADMIN W、维保经理 R、质量 R、班组长本组 → `record:read`
 *   与之一致（含班组长），故不用 `report:retry`（后者不含质量）。
 * - 人员管理 `/register/workers`：平台登记人员账号，属人员档案维护 → `employee:read`。
 */
export const routes = [
  { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: DashboardView, meta: { title: '监控看板', perm: 'console:read' } },
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
