import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// Task 10（D10/M12 + 设计 §4.4）：工单调度「维保工单池」页签——
// 未认领池工单显示「待接单 · 区域名」，已认领显示认领人；行内「退回池」（order:dispatch）
// 调 POST /admin/orders/{id}/release-to-pool；已签到工单退回的二次确认须含「已签到留痕将转入审计」。
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
import DispatchView from '../views/DispatchView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/dispatch', component: { template: '<div/>' } },
  { path: '/order/:id', component: { template: '<div/>' } }
] })

const POOL = {
  list: [
    {
      id: 'wo_pool_1', orderNo: 'WB20261121001', elevatorCode: 'DT001', elevatorName: 'A栋1#梯',
      projectName: '世纪大厦', workType: '半月保', workTypeCode: 'HM', planTime: '2026-11-21 09:00:00',
      status: 'PENDING', workerName: '', workerPlatformId: '', assistantName: '',
      regionId: 'rg_yubei', regionName: '渝北区'
    },
    {
      id: 'wo_pool_2', orderNo: 'WB20261121002', elevatorCode: 'DT002', elevatorName: 'B栋2#梯',
      projectName: '世纪大厦', workType: '季度保', workTypeCode: 'TM', planTime: '2026-11-21 09:00:00',
      status: 'PROCESSING', workerName: '张伟', workerPlatformId: 'pid_zhang', assistantName: '李四',
      regionId: 'rg_yubei', regionName: '渝北区', principalCheckedIn: true, assistantCheckedIn: true
    },
    {
      id: 'wo_done', orderNo: 'WB20261120003', elevatorCode: 'DT003', elevatorName: 'C栋3#梯',
      projectName: '世纪大厦', workType: '半月保', workTypeCode: 'HM', planTime: '2026-11-20 09:00:00',
      status: 'DONE', workerName: '张伟', workerPlatformId: 'pid_zhang', assistantName: '李四',
      regionId: 'rg_jiangbei', regionName: '江北区'
    }
  ],
  total: 3
}

async function mountDispatch() {
  const w = mount(DispatchView, { global: { plugins: [router] } })
  await flushPromises()
  return w
}
async function openPoolTab(w) {
  const tab = w.findAll('button').find(b => b.text().includes('维保工单池'))
  expect(tab, '工单调度应有「维保工单池」页签').toBeTruthy()
  await tab.trigger('click')
  await flushPromises()
}

describe('DispatchView 维保工单池页签（D2/D10）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'MAINT_SUP' // order:view_pool / order:dispatch 均 Y
    router.push('/')
    http.get.mockReset()
    http.get.mockImplementation((url) => {
      if (url === '/work-orders') return Promise.resolve(POOL)
      if (url === '/faults') return Promise.resolve([])
      if (url === '/employees') return Promise.resolve([])
      if (url === '/elevators') return Promise.resolve({ list: [] })
      if (url === '/settings') return Promise.resolve({})
      return Promise.resolve([])
    })
    http.post.mockReset()
    http.post.mockResolvedValue({ ok: true, released: true, status: 'PENDING' })
  })

  it('未认领池工单显示「待接单 · 区域名」，已认领显示认领人', async () => {
    const w = await mountDispatch()
    await openPoolTab(w)
    expect(w.text()).toContain('待接单 · 渝北区')
    expect(w.text()).toContain('张伟')
    // 池页签不再展示「计划绑定人」时代的列
    expect(w.text()).not.toContain('待派')
  })

  it('未认领工单行内「退回池」调用 POST /admin/orders/{id}/release-to-pool', async () => {
    const w = await mountDispatch()
    await openPoolTab(w)
    const btn = w.findAll('button').find(b => b.text().includes('退回池') && b.element.closest('tr')?.textContent.includes('WB20261121001'))
    expect(btn, '未认领工单行应有「退回池」按钮').toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    const modal = w.findAll('.modal').find(m => m.text().includes('退回池'))
    expect(modal, '应弹出退回池二次确认').toBeTruthy()
    // 勾选二次确认后提交
    await modal.find('input[type="checkbox"]').setValue(true)
    const ok = modal.findAll('button').find(b => b.text().includes('确认退回池'))
    await ok.trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/admin/orders/wo_pool_1/release-to-pool', {})
  })

  it('已签到（PROCESSING）工单退回池的二次确认含「已签到留痕将转入审计」', async () => {
    const w = await mountDispatch()
    await openPoolTab(w)
    const btn = w.findAll('button').find(b => b.text().includes('退回池') && b.element.closest('tr')?.textContent.includes('WB20261121002'))
    expect(btn, 'PROCESSING 工单行应有「退回池」按钮').toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    const modal = w.findAll('.modal').find(m => m.text().includes('退回池'))
    expect(modal.text()).toContain('已签到留痕将转入审计')
  })

  it('DONE 工单不渲染「退回池」入口（后端 422 兜底，前端不给出路）', async () => {
    const w = await mountDispatch()
    await openPoolTab(w)
    const doneRow = w.findAll('tr').find(tr => tr.text().includes('WB20261120003'))
    expect(doneRow, '列表应含 DONE 工单行').toBeTruthy()
    expect(doneRow.text()).not.toContain('退回池')
  })
})
