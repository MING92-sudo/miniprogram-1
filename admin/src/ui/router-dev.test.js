import { describe, it, expect } from 'vitest'
import { routes } from '../router'

// M5：旧管理端下线，单一 AppShell 承载全部 23 视图
describe('M5 single-shell router', () => {
  it('AppShell branch has dashboard + M3/M4/M5 views', () => {
    const shell = routes.find(r => r.path === '/' && r.children)
    const paths = shell.children.map(c => c.path)
    expect(paths).toContain('dashboard')
    expect(paths).toContain('org')
    expect(paths).toContain('handovers')
    expect(paths).toContain('stats')
    expect(paths).toContain('audit')
    expect(paths).toContain('settings')
  })
  it('legacy EP branch retired', () => {
    expect(routes.find(r => r.path === '/legacy')).toBeUndefined()
  })
  it('login/403/404 present', () => {
    expect(routes.find(r => r.path === '/login')).toBeTruthy()
    expect(routes.find(r => r.path === '/403')).toBeTruthy()
  })
})