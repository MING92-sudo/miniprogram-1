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

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

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
          items: [{ id: 'ci_A-1-1', itemCode: 'A-1-1', name: '制动器', result: 'NORMAL', problemCode: '', value: '1', valueUnit: 'mm', abnormalDesc: '' }],
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
    await w.get('[data-testid="amend-item-problem"]').setValue('S1')
    await w.get('[data-testid="amend-todo"]').setValue('复测并调整制动间隙')
    await w.get('[data-testid="amend-safety-barrier"]').setValue(true)
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledWith('/records/ur_1/amend', expect.objectContaining({
      reason: '更正制动器读数',
      todoDesc: '复测并调整制动间隙',
      items: [expect.objectContaining({
        name: '制动器', result: 'ABNORMAL', value: '2.5', abnormalDesc: '制动间隙超标', problemCode: 'S1'
      })],
      safety: expect.objectContaining({ warning: true, barrier: true, twoPerson: true })
    }))
  })

  it('明细加载失败时禁止提交空检查项', async () => {
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: [archivedRow], total: 1 })
      if (url === '/admin/records/ur_1') return Promise.reject(new Error('detail unavailable'))
      return Promise.resolve({})
    })
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('发起修订')).trigger('click')
    await flushPromises()
    await w.get('[data-testid="amend-reason"]').setValue('仍然不能提交')
    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('A请求迟到不会解除B请求的加载门禁', async () => {
    const a = deferred()
    const b = deferred()
    const rowB = { ...archivedRow, id: 'ur_2', originalRecordId: 'REC-0002' }
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: [archivedRow, rowB], total: 2 })
      if (url === '/admin/records/ur_1') return a.promise
      if (url === '/admin/records/ur_2') return b.promise
      return Promise.resolve({})
    })
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    const buttons = w.findAll('button').filter((x) => x.text().includes('发起修订'))
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    a.resolve({ ...archivedRow, items: [{ id: 'ci_A', itemCode: 'A', result: 'NORMAL' }] })
    await flushPromises()
    await w.get('[data-testid="amend-reason"]').setValue('B尚未就绪')
    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    b.resolve({ ...rowB, items: [{ id: 'ci_B', itemCode: 'B', result: 'NORMAL' }], safety: {} })
    await flushPromises()
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][0]).toBe('/records/ur_2/amend')
  })

  it('双击确认只提交一次', async () => {
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('发起修订')).trigger('click')
    await flushPromises()
    await w.get('[data-testid="amend-reason"]').setValue('只提交一次')
    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    modal.vm.$emit('confirm')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
  })

  it('异常项缺少隐患码时禁止提交', async () => {
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    await w.findAll('button').find((b) => b.text().includes('发起修订')).trigger('click')
    await flushPromises()
    await w.get('[data-testid="amend-reason"]').setValue('异常缺码')
    await w.get('[data-testid="amend-item-result"]').setValue('ABNORMAL')
    await w.get('[data-testid="amend-item-abnormal"]').setValue('制动间隙异常')
    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('保存中的旧请求不会关闭或误报新目标', async () => {
    const rowB = { ...archivedRow, id: 'ur_2', originalRecordId: 'REC-0002' }
    const pending = deferred()
    http.post.mockImplementation(() => pending.promise)
    http.get.mockImplementation((url) => {
      if (url === '/admin/records') return Promise.resolve({ list: [archivedRow, rowB], total: 2 })
      if (url === '/admin/records/ur_1') return Promise.resolve({ ...archivedRow, items: [{ id: 'ci_A', itemCode: 'A', result: 'NORMAL' }] })
      if (url === '/admin/records/ur_2') return Promise.resolve({ ...rowB, items: [{ id: 'ci_B', itemCode: 'B', result: 'NORMAL' }] })
      return Promise.resolve({})
    })
    const w = mount(RecordsView, { global: { plugins: [router] } })
    await flushPromises()
    const buttons = w.findAll('button').filter((x) => x.text().includes('发起修订'))
    await buttons[0].trigger('click')
    await flushPromises()
    await w.get('[data-testid="amend-reason"]').setValue('A提交中')
    const modal = w.findAllComponents(Modal).find((m) => m.props('title') === '发起归档后修订')
    modal.vm.$emit('confirm')
    await buttons[1].trigger('click')
    await flushPromises()
    pending.resolve({})
    await flushPromises()
    expect(modal.props('open')).toBe(true)
    expect(modal.props('sub')).toBe('REC-0002')
  })
})
