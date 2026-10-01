/**
 * 路由守卫纯函数（Vitest 覆盖 auth 守卫）：
 * 未登录访问受保护页 → /login（携带 redirect）；已登录访问 /login → /dashboard；其余放行。
 */
export function resolveRoute(to, authState) {
  const isPublic = Boolean(to.meta && to.meta.public)
  if (!isPublic && !authState.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authState.token) {
    return { path: '/dashboard' }
  }
  return true
}
