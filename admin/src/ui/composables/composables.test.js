import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { usePermission } from './usePermission'
import { usePagination } from './usePagination'

describe('usePermission', () => {
  it('gate maps Y/DENY/C to allow/deny/conditional', () => {
    const { gate } = usePermission(ref('INST_CLERK'))
    expect(gate('handover:review')).toBe('deny')
    expect(gate('handover:view')).toBe('conditional')
    const mgr = usePermission(ref('INST_MGR'))
    expect(mgr.gate('handover:review')).toBe('allow')
  })
  it('unknown role is deny', () => {
    expect(usePermission(ref('HACKER')).gate('handover:view')).toBe('deny')
  })
})

describe('usePagination', () => {
  it('computes pageCount and offset', () => {
    const p = usePagination(ref(20), 8)
    expect(p.pageCount.value).toBe(3)
    expect(p.offset.value).toBe(0)
    p.setPage(2)
    expect(p.offset.value).toBe(8)
  })
  it('clamps page within range and resets on size change', () => {
    const p = usePagination(ref(20), 8)
    p.setPage(99)
    expect(p.page.value).toBe(3)
    p.setSize(16)
    expect(p.page.value).toBe(1)
    expect(p.pageCount.value).toBe(2)
  })
  it('slice returns the current page rows', () => {
    const rows = Array.from({ length: 20 }, (_, i) => i)
    const p = usePagination(ref(20), 8)
    expect(p.slice(rows)).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
    p.setPage(3)
    expect(p.slice(rows)).toEqual([16, 17, 18, 19])
  })
})