import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn(),
  idempotencyKey: () => 'idem-test',
  API_BASE: '/api',
  resolveEnvelope: (b) => b,
  isAuthError: () => false
}))

import http from '../api/client'
import BoardView from './BoardView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: { template: '<div/>' } },
    { path: '/dashboard', component: { template: '<div/>' } }
  ]
})

describe('BoardView 今日闭环按 Asia/Shanghai 本地日统计', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-21T16:30:00Z')) // 2026-11-22 00:30 GMT+8
    router.push('/')
    http.get.mockReset()
    http.get.mockImplementation((url) => {
      if (url === '/work-orders') {
        return Promise.resolve([
          { id: 'today', status: 'DONE', checkoutTime: '2026-11-22 00:10:00' },
          { id: 'yesterday', status: 'DONE', checkoutTime: '2026-11-21 23:50:00' }
        ])
      }
      return Promise.resolve([])
    })
  })
  afterEach(() => { vi.useRealTimers() })

  it('00:30 GMT+8 时，当日 00:10 结单计入今日，前一日 23:50 不计入', async () => {
    const w = mount(BoardView, { global: { plugins: [router] } })
    await flushPromises()
    expect(w.vm.closedToday.map((o) => o.id)).toEqual(['today'])
    w.unmount()
  })
})
