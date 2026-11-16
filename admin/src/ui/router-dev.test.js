import { describe, it, expect } from 'vitest'
import { routes } from '../router'

// 单一 AppShell 承载核心视图；二期监管与运营 4 页（board 独立全屏）
describe('single-shell router', () => {
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
  it('phase-2 routes present (sync/msg-templates/reviews in shell, board standalone)', () => {
    const shell = routes.find(r => r.path === '/' && r.children)
    const paths = shell.children.map(c => c.path)
    expect(paths).toContain('sync')
    expect(paths).toContain('msg-templates')
    expect(paths).toContain('reviews')
    expect(paths).not.toContain('board')
    expect(routes.find(r => r.path === '/board')).toBeTruthy()
  })
  it('legacy EP branch retired', () => {
    expect(routes.find(r => r.path === '/legacy')).toBeUndefined()
  })
  it('login/403/404 present', () => {
    expect(routes.find(r => r.path === '/login')).toBeTruthy()
    expect(routes.find(r => r.path === '/403')).toBeTruthy()
  })
})