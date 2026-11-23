import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import { useAuthStore } from '../stores/auth'
import FailedRecordsView from './FailedRecordsView.vue'
import DataTable from '../ui/DataTable.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

function rec(i, status) {
  return { id: 'rec_' + i, originalRecordId: 'REC-' + i, elevatorCode: 'EM-' + i, workTypeCode: 'HM', reportStatus: status }
}

// 第 1 页 20 条，第 2 页 6 条；最旧一条 FAILED 落在第 2 页末尾，另有一条 UNKNOWN 不应出现
const page1 = Array.from({ length: 20 }, (_, i) => rec(i, i % 2 === 0 ? 'FAILED' : 'PENDING'))
const page2 = [rec(20, 'PENDING'), rec(21, 'FAILED'), rec(22, 'PENDING'), rec(23, 'PENDING'), rec(24, 'FAILED'), rec(25, 'UNKNOWN')]
const TOTAL = 26
const OLDEST_FAILED = 'REC-24'

function mountView() {
  return mount(FailedRecordsView, { global: { plugins: [router] } })
}

describe('FailedRecordsView 异常记录完整分页', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'PLATFORM_ADMIN'
    useAuthStore().roles = ['PLATFORM_ADMIN']
    router.push('/')
    http.get.mockReset()
    http.get.mockImplementation((url, config) => {
      if (url === '/admin/records') {
        return Promise.resolve({ list: config.params.page === 1 ? page1 : page2, total: TOTAL })
      }
      return Promise.resolve({})
    })
  })

  it('带 FAILED,PENDING 过滤逐页拉取直到 total，不截断第一页', async () => {
    const w = mountView()
    await flushPromises()
    const calls = http.get.mock.calls.filter(([url]) => url === '/admin/records')
    expect(calls.length).toBe(2)
    for (const [, config] of calls) {
      expect(config.params.reportStatus).toBe('FAILED,PENDING')
    }
    expect(calls.map(([, config]) => config.params.page)).toEqual([1, 2])
    const tableRows = w.findComponent(DataTable).props('rows')
    expect(tableRows).toHaveLength(TOTAL - 1)
    expect(tableRows.map((r) => r.originalRecordId)).toContain(OLDEST_FAILED)
  })

  it('只渲染 FAILED / PENDING，UNKNOWN 不进入异常清单', async () => {
    const w = mountView()
    await flushPromises()
    const tableRows = w.findComponent(DataTable).props('rows')
    expect(tableRows.every((r) => r.reportStatus === 'FAILED' || r.reportStatus === 'PENDING')).toBe(true)
    expect(tableRows.map((r) => r.originalRecordId)).not.toContain('REC-25')
    expect(w.text()).not.toContain('REC-25')
  })

  it('最旧一条 FAILED 记录可翻到最后一页看到，FAILED 行提供逐条人工重报', async () => {
    const w = mountView()
    await flushPromises()
    // 25 行按每页 8 条共 4 页，翻到第 4 页
    const lastPageBtn = w.findAll('.pager .pg').find((b) => b.text() === '4')
    await lastPageBtn.trigger('click')
    await flushPromises()
    const row = w.findAll('tbody tr').find((tr) => tr.text().includes(OLDEST_FAILED))
    expect(row).toBeTruthy()
    expect(row.text()).toContain('人工重报')
  })
})
