import { describe, expect, it } from 'vitest'
import { resolveRoute } from './guard'

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

  const rolesRoute = {
    path: '/roles', fullPath: '/roles',
    meta: { title: '角色权限', perm: 'role:assign', permByTab: { grants: 'invite:manage' } }
  }
  const settingsRoute = {
    path: '/settings', fullPath: '/settings',
    meta: { title: '系统配置', perm: 'config:manage', permByTab: { regions: 'region:view', templates: 'template:view' } }
  }

  it('分页权限命中：有 invite:manage 无 role:assign（MAINT_MGR）→ /roles?tab=grants 放行', () => {
    const to = { ...rolesRoute, query: { tab: 'grants' }, fullPath: '/roles?tab=grants' }
    expect(resolveRoute(to, { token: 'jwt', role: 'MAINT_MGR' })).toBe(true)
  })

  it('分页权限未命中：MAINT_MGR 访问 /roles?tab=grants 以外的主页 → /403', () => {
    const to = { ...rolesRoute, query: {}, fullPath: '/roles' }
    // MAINT_MGR 有 invite:manage，无 tab 默认落地任一权限放行，视图内收敛到 grants
    expect(resolveRoute(to, { token: 'jwt', role: 'MAINT_MGR' })).toBe(true)
    const deny = { ...rolesRoute, query: {}, fullPath: '/roles' }
    expect(resolveRoute(deny, { token: 'jwt', role: 'MAINT_TECH' }).path).toBe('/403')
  })

  it('宿主权限只看宿主分页：MAINT_QUALITY（template:view=C）→ /settings?tab=templates 放行、无 tab 也放行', () => {
    const byTab = { ...settingsRoute, query: { tab: 'templates' }, fullPath: '/settings?tab=templates' }
    expect(resolveRoute(byTab, { token: 'jwt', role: 'MAINT_QUALITY' })).toBe(true)
    const landing = { ...settingsRoute, query: {}, fullPath: '/settings' }
    expect(resolveRoute(landing, { token: 'jwt', role: 'MAINT_QUALITY' })).toBe(true)
  })

  it('无任何相关权限 → /403（MAINT_TECH 访问 /settings）', () => {
    const landing = { ...settingsRoute, query: {}, fullPath: '/settings' }
    expect(resolveRoute(landing, { token: 'jwt', role: 'MAINT_TECH' }).path).toBe('/403')
  })

  it('平台管理员全权限照常放行', () => {
    const to = { ...rolesRoute, query: { tab: 'grants' }, fullPath: '/roles?tab=grants' }
    expect(resolveRoute(to, { token: 'jwt', role: 'PLATFORM_ADMIN' })).toBe(true)
  })
})
