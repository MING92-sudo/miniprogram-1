import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePerspectiveStore } from './stores/perspective'
import { useAuthStore } from '../stores/auth'
import { MENU_GROUPS, IMPLEMENTED_PATHS, visibleMenus } from './menu-config'
import { routes } from '../router'

describe('perspective store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('derives role and scope from the logged-in account only', () => {
    const auth = useAuthStore()
    const p = usePerspectiveStore()

    auth.role = 'MAINT_MGR'
    expect(p.activeRole).toBe('MAINT_MGR')
    expect(p.scope).toBe('ALL')

    auth.role = 'LEADER'
    expect(p.activeRole).toBe('LEADER')
    expect(p.scope).toBe('TEAM')
  })
  it('cannot switch role from the front end', () => {
    const auth = useAuthStore()
    const p = usePerspectiveStore()
    auth.role = 'MAINT_MGR'
    p.setRole('MAINT_SUP')
    p.setRoleSet(['PLATFORM_ADMIN'])
    expect(p.activeRole).toBe('MAINT_MGR')
    expect(p.roleSet).toEqual(['MAINT_MGR'])
  })
})

describe('menu-config（2026-11-21 精简 IA：7 组，合并页 Tab 承载）', () => {
  it('has 7 groups', () => {
    expect(MENU_GROUPS.map(g => g.title)).toEqual(['概览', '电梯管理', '项目管理', '维保管理', '公司管理', '数据对接', '系统'])
  })
  it('合并/下线的页面不在菜单（仍存在的页面仅可直达或重定向）', () => {
    const paths = MENU_GROUPS.flatMap(g => g.items.map(i => i.path))
    for (const p of ['/msg-templates', '/reviews', '/board', '/contracts', '/grants', '/regions', '/templates', '/filings']) {
      expect(paths).not.toContain(p)
    }
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
// 防「路由已加、菜单不显示」：新页面漏登记 IMPLEMENTED_PATHS 时菜单会静默消失（使用单位档案曾如此）。
describe('菜单白名单完整性', () => {
  const registered = (() => {
    const set = new Set()
    for (const r of routes) {
      set.add(r.path)
      for (const c of r.children || []) set.add((r.path === '/' ? '' : r.path) + '/' + c.path)
    }
    return set
  })()

  it('已注册路由的菜单项都在 IMPLEMENTED_PATHS 中', () => {
    const missing = MENU_GROUPS.flatMap(g => g.items)
      .filter(i => registered.has(i.path) && !IMPLEMENTED_PATHS.includes(i.path))
      .map(i => i.path)
    expect(missing).toEqual([])
  })

  it('使用单位档案可见且受 client:edit 约束', () => {
    const item = MENU_GROUPS.flatMap(g => g.items).find(i => i.path === '/use-units')
    expect(item).toBeTruthy()
    expect(item.perm).toBe('client:edit')
    const paths = visibleMenus('SYS_ADMIN', IMPLEMENTED_PATHS).flatMap(g => g.items.map(i => i.path))
    expect(paths).toContain('/use-units')
  })
})
