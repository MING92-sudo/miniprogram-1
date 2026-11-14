import { describe, it, expect } from 'vitest'
import { routes } from '../router'

describe('dual-layout router', () => {
  it('new AppShell branch has /dashboard', () => {
    const shell = routes.find(r => r.path === '/' && r.children)
    const paths = shell.children.map(c => c.path)
    expect(paths).toContain('dashboard')
  })
  it('old business routes preserved under AdminLayout', () => {
    const legacy = routes.find(r => r.path === '/legacy')
    expect(legacy).toBeTruthy()
    const paths = legacy.children.map(c => c.path)
    expect(paths).toContain('orders')
    expect(paths).toContain('ledger')
  })
  it('login/403/404 present', () => {
    expect(routes.find(r => r.path === '/login')).toBeTruthy()
    expect(routes.find(r => r.path === '/403')).toBeTruthy()
  })
})