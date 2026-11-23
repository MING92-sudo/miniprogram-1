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
import RecordsView from '../views/RecordsView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

// 行形状镜像后端 AdminService.recordRow / mock /admin/records 投影（含 archiveFileId）
const base = {
  elevatorName: '1# 客梯', elevatorCode: 'EM-1', workType: '半月维保', workTypeCode: 'HM',
  workerName: '张伟', assistantName: '李强',
  checkinTime: '2026-11-19 08:00:00', checkoutTime: '2026-11-19 10:00:00', duration: '2小时',
  problemCodes: ['S0'], reportStatus: 'SUBMITTED', uploadStatus: 'PENDING', retryCount: 0,
  confirmStatus: 'CONFIRMED', refuseCode: '', refuseReason: '', satisfaction: 0,
  createdAt: '2026-11-19 10:00:00'
}
const pendingArchiveRow = { ...base, id: 'ur_p1', originalRecordId: 'REC-0101', lifecycleStatus: 'SIGNED', archiveFileId: '' }
const signedArchivedRow = { ...base, id: 'ur_p2', originalRecordId: 'REC-0102', lifecycleStatus: 'SIGNED', archiveFileId: 'file_1' }
const archivedRow = { ...base, id: 'ur_p3', originalRecordId: 'REC-0103', lifecycleStatus: 'ARCHIVED', archiveFileId: 'file_2' }

function mountView() {
  return mount(RecordsView, { global: { plugins: [router] } })
}

describe('RecordsView 待归档标签', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    router.push('/')
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') {
        return Promise.resolve({ list: [pendingArchiveRow, signedArchivedRow, archivedRow], total: 3 })
      }
      return Promise.resolve({})
    })
  })

  it('已签字且归档文件缺失显示待归档，已带归档文件或已归档不显示', async () => {
    const w = mountView()
    await flushPromises()
    const rows = w.findAll('tbody tr')
    const pending = rows.find((tr) => tr.text().includes('REC-0101'))
    const signed = rows.find((tr) => tr.text().includes('REC-0102'))
    const archived = rows.find((tr) => tr.text().includes('REC-0103'))
    expect(pending.text()).toContain('待归档')
    expect(signed.text()).not.toContain('待归档')
    expect(archived.text()).not.toContain('待归档')
  })
})
