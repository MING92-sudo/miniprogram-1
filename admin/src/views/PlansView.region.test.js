import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import { useAuthStore } from '../stores/auth'
import PlansView from '../views/PlansView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/plans', component: { template: '<div/>' } }
] })

const PROJECTION = [
  {
    elevatorId: 'el_1', elevatorCode: 'DT001', elevatorName: 'A栋1#梯', projectName: '世纪大厦',
    regionId: 'rg_yubei', regionStaffed: true, lastDoneAt: '2026-11-06 09:00:00',
    dueDate: '2026-11-21', workTypeCode: 'HM', daysToDue: 0, status: 'TODAY', statusText: '今日到期'
  },
  {
    elevatorId: 'el_2', elevatorCode: 'DT002', elevatorName: 'B栋2#梯', projectName: '世纪大厦',
    regionId: 'rg_empty', regionStaffed: false, lastDoneAt: '2026-11-10 09:00:00',
    dueDate: '2026-11-25', workTypeCode: 'HM', daysToDue: 4, status: 'SOON', statusText: '4 天后到期'
  }
]
const REGIONS = [{ id: 'rg_yubei', name: '渝北区' }, { id: 'rg_empty', name: '两江新区' }]

describe('PlansView 区域覆盖提示（regionStaffed，D2）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'MAINT_SUP' // plan:view = Y
    router.push('/')
    http.get.mockReset()
    http.get.mockImplementation((url) => {
      if (url === '/admin/plans/projection') return Promise.resolve(PROJECTION)
      if (url === '/admin/plans/delays') return Promise.resolve({ list: [] })
      if (url === '/regions') return Promise.resolve(REGIONS)
      return Promise.resolve([])
    })
  })

  it('regionStaffed=false 的行显示「区域无人」预警（regionId 解析为区域名）', async () => {
    const w = mount(PlansView, { global: { plugins: [router] } })
    await flushPromises()
    const emptyRow = w.findAll('tr').find(tr => tr.text().includes('DT002'))
    expect(emptyRow, '列表应含 DT002 行').toBeTruthy()
    expect(emptyRow.text()).toContain('两江新区')
    expect(emptyRow.text()).toContain('区域无人')
    const staffedRow = w.findAll('tr').find(tr => tr.text().includes('DT001'))
    expect(staffedRow.text()).toContain('渝北区')
    expect(staffedRow.text()).not.toContain('区域无人')
  })

  it('不再展示计划绑定人列与「未绑定」拦截文案（池化派单不带人）', async () => {
    const w = mount(PlansView, { global: { plugins: [router] } })
    await flushPromises()
    expect(w.text()).not.toContain('无配合人')
    expect(w.text()).not.toContain('未绑定')
    expect(w.text()).not.toContain('不可派单')
  })
})
