// 演示视角基准集（原型 SWITCH_ROLES 对齐，账号换成本系统 mock/种子真实演示账号）。
// 仅用于登录页快捷填充与顶栏视角切换演示；真实权限仍以服务端下发为准。
export const DEMO_PERSONAS = [
  { role: 'PLATFORM_ADMIN', name: '郑浩', phone: '13800000009', scope: 'ALL', scopeLabel: '全量（运维）' },
  { role: 'MAINT_MGR', name: '赵经理', phone: '13800000015', scope: 'ALL', scopeLabel: '全部维保项目' },
  { role: 'MAINT_SUP', name: '陈刚', phone: '13800000002', scope: 'TEAM', scopeLabel: '维保一班（本班组）' },
  { role: 'INST_MGR', name: '张工', phone: '13800000099', scope: 'ORG', scopeLabel: '安装部及下级' },
  { role: 'CLIENT_ADMIN', name: '张伟', phone: '13800000012', scope: 'ORG_FLAT', scopeLabel: '启元物业（本单位）' }
]
export const DEMO_PASSWORD = '123456'
export function personaByRole(role) { return DEMO_PERSONAS.find(p => p.role === role) || null }