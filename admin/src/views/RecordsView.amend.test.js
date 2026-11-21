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
import Modal from '../ui/Modal.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

const archivedRow = {
  id: 'ur_1', originalRecordId: 'REC-0001', elevatorCode: 'EM-1', elevatorName: '1# 客梯',
  workType: '半月维保', workerName: '张伟', assistantName: '李强', problemCodes: ['S0'],
  confirmStatus: 'CONFIRMED', lifecycleStatus: 'ARCHIVED', reportStatus: 'REPORTED',
  createdAt: '2026-11-19 10:00:00'
}

describe('RecordsView 归档修订', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('admin_role', 'PLATFORM_ADMIN')
    router.push('/')
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: [archivedRow], total: 1 })
      if (url === '/admin/records/ur_1') {
        return Promise.resolve({
          ...archivedRow,
          items: [{ name: '制动器', result: 'NORMAL', value: '1', valueUnit: 'mm', abnormalDesc: '' }],
          safety: { warning: true, barrier: false, powerOff: true, twoPerson: true },
          todoDesc: '原待办', photos: [], context: {}
        })
      }
      return Promise.resolve({})
    })
    http.post.mockClear()
  })

  it('加载明细并提交检查项、待办和安全防护完整变更集', async () => {
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    const amendButton = w.findAll('button').find((b) => b.text().includes('发起修订'))
    expect(amendButton).toBeTruthy()
    await amendButton.trigger('click')
    await flushPromises()

    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    expect(modal.props('open')).toBe(true)
    await w.get('[data-testid="amend-reason"]').setValue('更正制动器读数')
    await w.get('[data-testid="amend-item-result"]').setValue('ABNORMAL')
    await w.get('[data-testid="amend-item-value"]').setValue('2.5')
    await w.get('[data-testid="amend-item-abnormal"]').setValue('制动间隙超标')
    await w.get('[data-testid="amend-todo"]').setValue('复测并调整制动间隙')
    await w.get('[data-testid="amend-safety-barrier"]').setValue(true)
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledWith('/records/ur_1/amend', expect.objectContaining({
      reason: '更正制动器读数',
      todoDesc: '复测并调整制动间隙',
      items: [expect.objectContaining({
        name: '制动器', result: 'ABNORMAL', value: '2.5', abnormalDesc: '制动间隙超标'
      })],
      safety: expect.objectContaining({ warning: true, barrier: true, twoPerson: true })
    }))
  })
})
