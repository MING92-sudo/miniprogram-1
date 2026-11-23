import { describe, it, expect } from 'vitest'
import { ref, nextTick } from 'vue'
import { useTable } from './useTable'
import { useForm } from './useForm'
import { usePagination } from './usePagination'

const rows = ref([
  { id: 1, name: 'alpha', status: 'ACTIVE' },
  { id: 2, name: 'beta', status: 'DISABLED' },
  { id: 3, name: 'gamma', status: 'ACTIVE' }
])

describe('useTable', () => {
  it('three-state sort cycles asc/desc/none', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.toggleSort('name'); expect(t.sortDir.value).toBe('asc')
    t.toggleSort('name'); expect(t.sortDir.value).toBe('desc')
    t.toggleSort('name'); expect(t.sortDir.value).toBe('none')
  })
  it('search filters by keys', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.query.value = 'alp'
    expect(t.visibleRows.value.map(r => r.id)).toEqual([1])
  })
  it('filter + clearFilters', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.setFilter('status', 'ACTIVE')
    expect(t.visibleRows.value.map(r => r.id)).toEqual([1, 3])
    t.clearFilters()
    expect(t.visibleRows.value.length).toBe(3)
  })
  it('select and toggleAll', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.toggleSelect(1)
    expect(t.selected.value.has(1)).toBe(true)
    t.toggleAll()
    expect(t.allSelected.value).toBe(true)
    t.toggleAll()
    expect(t.selected.value.size).toBe(0)
  })
  it('pageCount 收缩时 page 收敛到最后一页', async () => {
    const nine = ref(Array.from({ length: 9 }, (_, i) => ({ id: i + 1, name: 'r' + i, status: 'ACTIVE' })))
    const t = useTable(nine, { searchKeys: ['name'] })
    t.pagination.setPage(2)
    expect(t.pagination.page.value).toBe(2)
    expect(t.visibleRows.value.length).toBe(1)
    nine.value = nine.value.slice(0, 8)
    await nextTick()
    expect(t.pagination.pageCount.value).toBe(1)
    expect(t.pagination.page.value).toBe(1)
    expect(t.visibleRows.value.length).toBe(8)
  })
})

describe('usePagination', () => {
  it('页码不越界，且 pageCount 减小时收敛', async () => {
    const total = ref(9)
    const p = usePagination(total, 8)
    p.setPage(2)
    expect(p.page.value).toBe(2)
    total.value = 8
    await nextTick()
    expect(p.pageCount.value).toBe(1)
    expect(p.page.value).toBe(1)
    expect(p.slice(Array.from({ length: 8 }, (_, i) => i)).length).toBe(8)
  })
})

describe('useForm', () => {
  const rules = {
    name: [(v) => (v ? '' : '姓名必填')],
    phone: [(v) => (/^1\d{10}$/.test(v || '') ? '' : '手机号格式错误')]
  }
  it('validateField sets error', () => {
    const f = useForm(rules)
    f.values.name = ''
    expect(f.validateField('name')).toBe('姓名必填')
    expect(f.errors.name).toBe('姓名必填')
  })
  it('validateAll aggregates and isValid', () => {
    const f = useForm(rules)
    f.values.name = '张三'; f.values.phone = '13800000000'
    expect(f.validateAll()).toBe(true)
    f.values.phone = '123'
    expect(f.validateAll()).toBe(false)
    expect(f.errors.phone).toBe('手机号格式错误')
  })
})