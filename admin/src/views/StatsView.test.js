import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn(),
  idempotencyKey: () => 'idem-test',
  API_BASE: '/api',
  resolveEnvelope: (b) => b,
  isAuthError: () => false
}))

import http from '../api/client'
import StatsView from './StatsView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

function metric(w, label) {
  return w.findAll('.kpi').find((k) => k.find('.kpi-k').text().includes(label))
}

describe('StatsView 分页记录统计口径', () => {
  let rows

  beforeEach(() => {
    setActivePinia(createPinia())
    router.push('/')
    http.get.mockReset()
    rows = Array.from({ length: 20 }, (_, i) => ({
      id: 'r' + i,
      checkAbnormal: 0,
      workerSigned: true,
      assistantSigned: true,
      lifecycleStatus: 'PENDING_REVIEW'
    }))
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: rows, total: 25, page: 1, size: 20 })
      if (url === '/stats') return Promise.resolve({ elevators: 0 })
      return Promise.resolve([])
    })
  })

  it('只取得部分记录时，不展示全量合格率、双签率和待复核数', async () => {
    const w = mount(StatsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(metric(w, '一次合格率').find('.kpi-n').text()).toBe('—')
    expect(metric(w, '双签完整率').find('.kpi-n').text()).toBe('—')
    expect(metric(w, '待复核积压').find('.kpi-n').text()).toBe('—')
    w.unmount()
  })

  it('记录总数不超过当前行数时，正常展示真实统计值', async () => {
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: rows, total: rows.length, page: 1, size: 20 })
      if (url === '/stats') return Promise.resolve({ elevators: 0 })
      return Promise.resolve([])
    })
    const w = mount(StatsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(metric(w, '一次合格率').find('.kpi-n').text()).toBe('100%')
    expect(metric(w, '双签完整率').find('.kpi-n').text()).toBe('100%')
    expect(metric(w, '待复核积压').find('.kpi-n').text()).toBe('20单')
    w.unmount()
  })
})
