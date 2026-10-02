import { describe, expect, it } from 'vitest'
import { resolveRoute } from './guard'

/** 路由守卫回归：未登录跳 /login；已登录访问 /login 跳 /dashboard */
describe('resolveRoute', () => {
  const protectedRoute = { path: '/dashboard', meta: { title: '监控看板' }, fullPath: '/dashboard' }
  const publicRoute = { path: '/login', meta: { public: true }, fullPath: '/login' }

  it('未登录访问受保护页 → /login 并携带 redirect', () => {
    const target = resolveRoute(protectedRoute, { token: '' })
    expect(target.path).toBe('/login')
    expect(target.query.redirect).toBe('/dashboard')
  })

  it('已登录访问受保护页放行', () => {
    expect(resolveRoute(protectedRoute, { token: 'jwt' })).toBe(true)
  })

  it('已登录访问 /login → /dashboard', () => {
    const target = resolveRoute(publicRoute, { token: 'jwt' })
    expect(target.path).toBe('/dashboard')
  })

  it('未登录访问 /login 放行', () => {
    expect(resolveRoute(publicRoute, { token: '' })).toBe(true)
  })

  it('角色不符访问 SYS_ADMIN 专属页 → /403，匹配则放行', () => {
    const sysOnly = { path: '/users', meta: { title: '用户权限', role: 'SYS_ADMIN' }, fullPath: '/users' }
    expect(resolveRoute(sysOnly, { token: 'jwt', role: 'LEADER' }).path).toBe('/403')
    expect(resolveRoute(sysOnly, { token: 'jwt', role: 'SYS_ADMIN' })).toBe(true)
  })
})
