import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePerspectiveStore } from './stores/perspective'
import { MENU_GROUPS, visibleMenus } from './menu-config'

describe('perspective store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('defaults to first role and derives scope', () => {
    const p = usePerspectiveStore()
    p.setRoleSet(['MAINT_MGR', 'MAINT_SUP'])
    expect(p.activeRole).toBe('MAINT_MGR')
    expect(p.scope).toBe('ALL')
    p.setRole('MAINT_SUP')
    expect(p.scope).toBe('TEAM')
  })
})

describe('menu-config（原型 GROUP_ORDER + 二期监管与运营组）', () => {
  it('has 9 groups incl. 监管与运营', () => {
    expect(MENU_GROUPS.length).toBe(9)
    expect(MENU_GROUPS.map(g => g.title)).toContain('监管与运营')
  })
  it('filters by permission and implemented flag', () => {
    const menus = visibleMenus('INST_MGR', ['/dashboard'])
    const paths = menus.flatMap(g => g.items.map(i => i.path))
    expect(paths).toContain('/dashboard')
    expect(paths).not.toContain('/contracts')
  })
  it('DENY role sees no privileged items', () => {
    const menus = visibleMenus('MAINT_TECH', ['/dashboard'])
    const paths = menus.flatMap(g => g.items.map(i => i.path))
    expect(paths).not.toContain('/settings')
  })
  it('phase-2 views mapped to real permission codes', () => {
    const ops = MENU_GROUPS.find(g => g.title === '监管与运营')
    const byPath = Object.fromEntries(ops.items.map(i => [i.path, i.perm]))
    expect(byPath['/sync']).toBe('report:view')
    expect(byPath['/msg-templates']).toBe('config:manage')
    expect(byPath['/reviews']).toBe('stats:view')
    expect(byPath['/board']).toBe('order:view_pool')
  })
})