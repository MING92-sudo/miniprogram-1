import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import DataTable from './DataTable.vue'

const columns = [{ key: 'name', label: '名称', sortable: true }, { key: 'status', label: '状态' }]
const rows = [{ id: 1, name: 'alpha', status: 'ACTIVE' }, { id: 2, name: 'beta', status: 'DISABLED' }]

describe('DataTable（原型 tableCard 对齐）', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('renders rows and headers', () => {
    const w = mount(DataTable, { props: { columns, rows } })
    expect(w.findAll('tbody tr').length).toBe(2)
    expect(w.text()).toContain('名称')
    expect(w.find('table.tbl').exists()).toBe(true)
  })
  it('shows skeleton when loading', () => {
    const w = mount(DataTable, { props: { columns, rows, loading: true } })
    expect(w.find('.skel-wrap').exists()).toBe(true)
  })
  it('shows empty state when no rows', () => {
    const w = mount(DataTable, { props: { columns, rows: [] } })
    expect(w.find('.state').exists()).toBe(true)
    expect(w.text()).toContain('当前筛选无数据')
  })
  it('search filters rows', async () => {
    const w = mount(DataTable, { props: { columns, rows, searchKeys: ['name'] } })
    await w.find('.search-box input').setValue('alp')
    expect(w.findAll('tbody tr').length).toBe(1)
  })
  it('select all shows bulk bar', async () => {
    const w = mount(DataTable, { props: { columns, rows, selectable: true, bulkActions: [{ code: 'user:disable', label: '停用' }] } })
    await w.find('thead .chk').setValue(true)
    expect(w.find('.bulkbar').exists()).toBe(true)
    expect(w.text()).toContain('已选 2')
  })
  it('emits row-click with row when clickable', async () => {
    const w = mount(DataTable, { props: { columns, rows, clickable: true } })
    await w.findAll('tbody tr')[1].trigger('click')
    expect(w.emitted('row-click')).toBeTruthy()
    expect(w.emitted('row-click')[0][0]).toEqual(rows[1])
  })
  it('does not emit row-click when not clickable', async () => {
    const w = mount(DataTable, { props: { columns, rows } })
    await w.findAll('tbody tr')[0].trigger('click')
    expect(w.emitted('row-click')).toBeFalsy()
  })
  it('sort toggles three states via header click', async () => {
    const w = mount(DataTable, { props: { columns, rows } })
    const th = w.find('th.sortable')
    await th.trigger('click')
    expect(w.find('th.sorted').exists()).toBe(true)
    await th.trigger('click')
    await th.trigger('click')
    expect(w.find('th.sorted').exists()).toBe(false)
  })
  it('footer shows count and page-size selector 8/16/32', () => {
    const w = mount(DataTable, { props: { columns, rows } })
    expect(w.find('.tbl-count').text()).toContain('共 2 条')
    expect(w.findAll('.tbl-foot select option').map(o => o.text())).toEqual(['8', '16', '32'])
  })
})