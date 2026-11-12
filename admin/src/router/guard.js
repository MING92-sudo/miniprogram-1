/**
 * 路由守卫纯函数（docs/09 §七.2：Vitest 覆盖 auth 守卫；docs/14 §9.4：越权进 /403）。
 * - 未登录访问受保护页 → /login（携带 redirect）
 * - 已登录访问 /login → /dashboard
 * - 有 token 但角色缺少该路由 `meta.perm` 能力 → /403（前端门禁只是体验，后端仍强校验）
 * - 其余放行
 */
import { can } from '../utils/permission'

export function resolveRoute(to, authState) {
  const isPublic = Boolean(to.meta && to.meta.public)
  if (!isPublic && !authState.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authState.token) {
    return { path: '/dashboard' }
  }
  const perm = to.meta && to.meta.perm
  // /403 本身 public，不存在回跳环；未登录分支已在上方拦截，这里只处理"有 token 但无能力"
  if (!isPublic && perm && !can(authState.role, perm)) {
    return { path: '/403' }
  }
  return true
}
