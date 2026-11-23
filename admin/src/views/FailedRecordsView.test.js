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

const FILTER = 'FAILED,PENDING,UNKNOWN,REPORTING,REUPLOADING,LEGACY_UPLOADING'
const STATUSES = ['FAILED', 'PENDING', 'UNKNOWN', 'REPORTING', 'REUPLOADING', 'LEGACY_UPLOADING']

function rec(i, status) {
  return { id: 'rec_' + i, originalRecordId: 'REC-' + i, elevatorCode: 'EM-' + i, workTypeCode: 'HM', reportStatus: status, retryCount: 3 }
}

// 第 1 页 20 条轮转六种异常状态，第 2 页 6 条；最旧一条 FAILED 落在第 2 页末尾，另有一条 REPORTED 不应出现
const page1 = Array.from({ length: 20 }, (_, i) => rec(i, STATUSES[i % STATUSES.length]))
const page2 = [rec(20, 'PENDING'), rec(21, 'FAILED'), rec(22, 'UNKNOWN'), rec(23, 'REPORTING'), rec(24, 'FAILED'), rec(25, 'REPORTED')]
const TOTAL = 26
const OLDEST_FAILED = 'REC-24'

const uploadLogList = [
  { id: 'log1', originalRecordId: 'REC-2', action: 'UPLOAD', platformCode: '200', platformMessage: '平台返回成功，本地结果待核对', status: 'UNKNOWN', requestDigest: 'digest-abc', createdAt: '2026-11-20 10:00:00' }
]

function mountView() {
  return mount(FailedRecordsView, { global: { plugins: [router] } })
}

function rowByCode(w, code) {
  return w.findAll('tbody tr').find((tr) => tr.text().includes(code))
}

describe('FailedRecordsView 异常记录完整分页', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'PLATFORM_ADMIN'
    useAuthStore().roles = ['PLATFORM_ADMIN']
    router.push('/')
    http.get.mockReset()
    http.post.mockReset()
    http.post.mockResolvedValue({ ok: true })
    http.get.mockImplementation((url, config) => {
      if (url === '/admin/records') {
        return Promise.resolve({ list: config.params.page === 1 ? page1 : page2, total: TOTAL })
      }
      if (url === '/reg/upload-logs') {
        return Promise.resolve({ list: uploadLogList, total: uploadLogList.length })
      }
      return Promise.resolve({})
    })
  })

  it('带全部异常状态过滤逐页拉取直到 total，不截断第一页', async () => {
    const w = mountView()
    await flushPromises()
    const calls = http.get.mock.calls.filter(([url]) => url === '/admin/records')
    expect(calls.length).toBe(2)
    for (const [, config] of calls) {
      expect(config.params.reportStatus).toBe(FILTER)
    }
    expect(calls.map(([, config]) => config.params.page)).toEqual([1, 2])
    const tableRows = w.findComponent(DataTable).props('rows')
    expect(tableRows).toHaveLength(TOTAL - 1)
    expect(tableRows.map((r) => r.originalRecordId)).toContain(OLDEST_FAILED)
  })

  it('停留态（UNKNOWN/REPORTING/REUPLOADING/LEGACY_UPLOADING）进入异常清单，REPORTED 不进入', async () => {
    const w = mountView()
    await flushPromises()
    const tableRows = w.findComponent(DataTable).props('rows')
    const ids = tableRows.map((r) => r.originalRecordId)
    expect(tableRows.every((r) => STATUSES.includes(r.reportStatus))).toBe(true)
    for (const code of ['REC-2', 'REC-3', 'REC-4', 'REC-5', 'REC-22', 'REC-23']) {
      expect(ids).toContain(code)
    }
    expect(ids).not.toContain('REC-25')
    expect(w.text()).not.toContain('REC-25')
  })

  it('FAILED 行显示人工重报，停留态行显示标记已上报/标记未上报，两者互斥', async () => {
    const w = mountView()
    await flushPromises()
    const failedRow = rowByCode(w, 'REC-0')
    expect(failedRow.text()).toContain('人工重报')
    expect(failedRow.text()).not.toContain('标记已上报')
    expect(failedRow.text()).not.toContain('标记未上报')
    for (const code of ['REC-2', 'REC-3', 'REC-4', 'REC-5']) {
      const stuckRow = rowByCode(w, code)
      expect(stuckRow.text()).toContain('标记已上报')
      expect(stuckRow.text()).toContain('标记未上报')
      expect(stuckRow.text()).not.toContain('人工重报')
    }
  })

  it('最旧一条 FAILED 记录可翻到最后一页看到，FAILED 行提供逐条人工重报', async () => {
    const w = mountView()
    await flushPromises()
    // 25 行按每页 8 条共 4 页，翻到第 4 页
    const lastPageBtn = w.findAll('.pager .pg').find((b) => b.text() === '4')
    await lastPageBtn.trigger('click')
    await flushPromises()
    const row = rowByCode(w, OLDEST_FAILED)
    expect(row).toBeTruthy()
    expect(row.text()).toContain('人工重报')
  })

  it('打开停留态记录抽屉：按 originalRecordId 拉取上报应答并渲染证据与 retryCount', async () => {
    const w = mountView()
    await flushPromises()
    await rowByCode(w, 'REC-2').trigger('click')
    await flushPromises()
    const logCalls = http.get.mock.calls.filter(([url]) => url === '/reg/upload-logs')
    expect(logCalls).toHaveLength(1)
    expect(logCalls[0][1].params).toMatchObject({ originalRecordId: 'REC-2', size: 5 })
    const drawer = w.find('.drawer')
    expect(drawer.text()).toContain('平台返回成功，本地结果待核对')
    expect(drawer.text()).toContain('200')
    expect(drawer.text()).toContain('2026-11-20 10:00:00')
    expect(drawer.text()).toContain('重试次数')
    expect(drawer.text()).toContain('3')
  })

  it('标记已上报：确认弹窗收集说明后调 reconcile（outcome=REPORTED）并刷新列表', async () => {
    const w = mountView()
    await flushPromises()
    const stuckRow = rowByCode(w, 'REC-2')
    const markBtn = stuckRow.findAll('button').find((b) => b.text().includes('标记已上报'))
    await markBtn.trigger('click')
    await flushPromises()
    const modal = w.find('.modal')
    expect(modal.exists()).toBe(true)
    await modal.find('textarea').setValue('平台控制台已核实记录存在')
    await modal.findAll('button').find((b) => b.text().includes('确认')).trigger('click')
    await flushPromises()
    const posts = http.post.mock.calls.filter(([url]) => url === '/platform/records/rec_2/reconcile')
    expect(posts).toHaveLength(1)
    expect(posts[0][1]).toEqual({ outcome: 'REPORTED', note: '平台控制台已核实记录存在' })
    const recordCalls = http.get.mock.calls.filter(([url]) => url === '/admin/records')
    expect(recordCalls.length).toBe(4)
  })
})
