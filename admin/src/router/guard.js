/**
 * 路由守卫纯函数（docs/09 §七.2；docs/14 §9.4：越权进 /403）。M5 起用 permission-v2（13 角色/86 码三态）。
 * - 未登录访问受保护页 → /login（携带 redirect）
 * - 已登录访问 /login → /dashboard
 * - 有 token 但角色缺少该路由 `meta.perm` 能力（Y/C 放行）→ /403（前端门禁只是体验，后端仍强校验）
 */
import { state } from '../ui/permission-v2'

function hasPerm(role, code) {
  try {
    const s = state(role, code)
    return s === 'Y' || s === 'C'
  } catch {
    return false
  }
}

export function resolveRoute(to, authState) {
  const isPublic = Boolean(to.meta && to.meta.public)
  if (!isPublic && !authState.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authState.token) {
    return { path: '/dashboard' }
  }
  const perm = to.meta && to.meta.perm
  if (!isPublic && perm && !hasPerm(authState.role, perm)) {
    return { path: '/403' }
  }
  return true
}