import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DataTable from './DataTable.vue'

const columns = [{ key: 'name', label: '名称', sortable: true }, { key: 'status', label: '状态' }]
const rows = [{ id: 1, name: 'alpha', status: 'ACTIVE' }, { id: 2, name: 'beta', status: 'DISABLED' }]

describe('DataTable', () => {
  it('renders rows and headers', () => {
    const w = mount(DataTable, { props: { columns, rows } })
    expect(w.findAll('tbody tr').length).toBe(2)
    expect(w.text()).toContain('名称')
  })
  it('shows skeleton when loading', () => {
    const w = mount(DataTable, { props: { columns, rows, loading: true } })
    expect(w.find('.skeleton').exists()).toBe(true)
  })
  it('shows empty state when no rows', () => {
    const w = mount(DataTable, { props: { columns, rows: [] } })
    expect(w.find('.empty').exists()).toBe(true)
  })
  it('search filters rows', async () => {
    const w = mount(DataTable, { props: { columns, rows, searchKeys: ['name'] } })
    await w.find('.dt-search').setValue('alp')
    expect(w.findAll('tbody tr').length).toBe(1)
  })
  it('select all shows bulk bar', async () => {
    const w = mount(DataTable, { props: { columns, rows, selectable: true, bulkActions: [{ code: 'user:disable', label: '停用' }] } })
    await w.find('.dt-check-all').setValue(true)
    expect(w.find('.dt-bulk').exists()).toBe(true)
    expect(w.text()).toContain('已选 2')
  })
})