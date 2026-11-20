import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// V56 拒签分类展示：列表行「拒签 + 分类码」标记、详情抽屉拒签分类/拒签原因区块
vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import RecordsView from '../views/RecordsView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

const refusedRow = {
  id: 'ur_r1', originalRecordId: 'REC-0001', elevatorCode: 'EM-1', elevatorName: '1# 客梯',
  workType: '半月维保', workerName: '张伟', assistantName: '李强', problemCodes: ['S0'],
  confirmStatus: 'PENDING', reportStatus: 'REPORTED', createdAt: '2026-11-19 10:00:00',
  lifecycleStatus: 'REFUSED_RETURNED', refuseCode: 'R3'
}
const normalRow = {
  id: 'ur_n1', originalRecordId: 'REC-0002', elevatorCode: 'EM-2', elevatorName: '2# 客梯',
  workType: '半月维保', workerName: '王芳', assistantName: '赵磊', problemCodes: ['S0'],
  confirmStatus: 'CONFIRMED', reportStatus: 'REPORTED', createdAt: '2026-11-18 10:00:00',
  lifecycleStatus: 'ARCHIVED', refuseCode: ''
}

function mountView() {
  return mount(RecordsView, { global: { plugins: [router] } })
}

describe('RecordsView 拒签分类展示（V56）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    router.push('/')
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: [refusedRow, normalRow], total: 2 })
      if (url === '/admin/records/ur_r1') {
        return Promise.resolve({
          ...refusedRow, items: [], photos: [], context: {},
          refuseReason: '现场照片不清晰或与实际不符：井道照片模糊'
        })
      }
      return Promise.resolve({})
    })
  })

  it('列表行展示拒签标记与分类码，正常行不展示', async () => {
    const w = mountView()
    await flushPromises()
    const rows = w.findAll('tbody tr')
    const refused = rows.find((tr) => tr.text().includes('REC-0001'))
    const normal = rows.find((tr) => tr.text().includes('REC-0002'))
    expect(refused.text()).toContain('拒签')
    expect(refused.text()).toContain('R3')
    expect(normal.text()).not.toContain('拒签')
  })

  it('详情抽屉展示拒签分类与拒签原因', async () => {
    const w = mountView()
    await flushPromises()
    const row = w.findAll('tbody tr').find((tr) => tr.text().includes('REC-0001'))
    await row.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('拒签分类')
    expect(w.text()).toContain('现场照片不清晰或与实际不符')
    expect(w.text()).toContain('井道照片模糊')
  })
})
