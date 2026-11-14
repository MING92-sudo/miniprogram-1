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

describe('menu-config', () => {
  it('has 8 groups', () => {
    expect(MENU_GROUPS.length).toBe(8)
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
})