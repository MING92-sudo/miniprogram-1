import { describe, it, expect } from 'vitest'
import { filterMenus, attachIcons } from './menu'
import { can } from './permission'

/**
 * 菜单纯函数（docs/14 §9.3）：服务端下发与本地兜底两条路径共用同一过滤逻辑。
 * 能力码与后端 MenuService/PermissionMatrix 同名，后端下发才是权威，这里只保证渲染口径一致。
 */
describe('filterMenus', () => {
  const MENUS = [
    { index: '/dashboard', title: '监控看板', perm: 'console:read' },
    { index: '/templates', title: '模板管理', perm: 'template:write' },
    {
      index: 'monitor',
      title: '工单监控',
      children: [
        { index: '/orders', title: '工单列表', perm: 'order:read' },
        { index: '/faults', title: '急修单', perm: 'fault:read' }
      ]
    },
    {
      index: 'archive',
      title: '档案管理',
      children: [
        { index: '/archive/use-units', title: '使用单位', perm: 'useunit:read' },
        { index: '/archive/elevators', title: '电梯档案', perm: 'ledger:read' }
      ]
    }
  ]

  const indexes = (menus) => menus.map((m) => m.index)
  const childrenOf = (menus, index) => menus.find((m) => m.index === index).children.map((c) => c.index)

  it('ADMIN 拿到完整菜单树', () => {
    const out = filterMenus(MENUS, (p) => can('ADMIN', p))
    expect(indexes(out)).toEqual(['/dashboard', '/templates', 'monitor', 'archive'])
    expect(childrenOf(out, 'monitor')).toEqual(['/orders', '/faults'])
  })

  it('班组长：无模板管理，档案管理只剩电梯档案', () => {
    const out = filterMenus(MENUS, (p) => can('LEADER', p))
    expect(indexes(out)).toEqual(['/dashboard', 'monitor', 'archive'])
    expect(childrenOf(out, 'archive')).toEqual(['/archive/elevators'])
  })

  it('分组内全不可见时整组消失（安装文员无工单域）', () => {
    const out = filterMenus(MENUS, (p) => can('INSTALL_CLERK', p))
    expect(indexes(out)).not.toContain('monitor')
    expect(indexes(out)).toContain('/dashboard')
  })

  it('未知角色只剩「无需能力」的项，实际为空', () => {
    expect(filterMenus(MENUS, (p) => can('NOPE', p))).toEqual([])
  })

  it('非法输入不崩：null/非数组返回空数组', () => {
    expect(filterMenus(null, () => true)).toEqual([])
    expect(filterMenus(undefined, () => true)).toEqual([])
  })

  it('服务端已过滤的菜单再过一遍是幂等的', () => {
    const server = [{ index: '/dashboard', title: '监控看板', perm: 'console:read' }]
    const out = filterMenus(server, (p) => can('ADMIN', p))
    expect(out).toEqual(server)
  })
})

describe('attachIcons', () => {
  const ICONS = { '/dashboard': 'IcoDashboard', monitor: 'IcoMonitor' }
  const FALLBACK = 'IcoFallback'

  it('按 index 挂图标，未知 index 用兜底', () => {
    const out = attachIcons(
      [{ index: '/dashboard', title: '看板' }, { index: '/x', title: '未知' }],
      ICONS, FALLBACK
    )
    expect(out[0].icon).toBe('IcoDashboard')
    expect(out[1].icon).toBe(FALLBACK)
  })

  it('不修改入参，返回新对象', () => {
    const input = [{ index: '/dashboard', title: '看板' }]
    const out = attachIcons(input, ICONS, FALLBACK)
    expect(input[0].icon).toBeUndefined()
    expect(out[0].icon).toBe('IcoDashboard')
  })

  it('非法输入返回空数组', () => {
    expect(attachIcons(null, ICONS, FALLBACK)).toEqual([])
  })
})
