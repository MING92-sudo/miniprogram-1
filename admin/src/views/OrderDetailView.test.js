import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

const routeRef = vi.hoisted(() => ({ current: null }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useRoute: () => routeRef.current }
})

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import OrderDetailView from './OrderDetailView.vue'
import Modal from '../ui/Modal.vue'
import Drawer from '../ui/Drawer.vue'
import { useAuthStore } from '../stores/auth'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function makeOrder(id, extra = {}) {
  return {
    id, faultNo: 'NO-' + id, elevatorCode: 'E-' + id, status: 'OPEN',
    createdAt: '2026-11-20 10:00:00', slaMinutes: 60, ...extra
  }
}

function setRoute(id) {
  routeRef.current = reactive({ params: { id }, path: `/order/${id}`, query: {} })
}

function switchTo(id) {
  routeRef.current.params.id = id
}

async function mountAtA(extra = {}) {
  setRoute('A')
  http.get.mockImplementation((url) => {
    if (url === '/employees') return Promise.resolve([{ id: 'w1', name: '张伟', jobNo: 'J1' }])
    if (url === '/faults/A') return Promise.resolve(makeOrder('A', extra))
    return new Promise(() => {})
  })
  const w = mount(OrderDetailView)
  await flushPromises()
  return w
}

async function openDispatch(w) {
  const btn = w.findAll('button').find((b) => b.text().trim() === '派单')
  expect(btn, 'OPEN 工单应有「派单」按钮').toBeTruthy()
  await btn.trigger('click')
  await flushPromises()
  const drawer = w.findAllComponents(Drawer).find((d) => d.props('title') === '派单 / 改派')
  await drawer.find('select').setValue('w1')
  await drawer.find('textarea').setValue('就近派单')
  return drawer.findAll('button').find((b) => b.text().includes('确认派单'))
}

function findModal(w, title) {
  const modal = w.findAllComponents(Modal).find((m) => m.props('title') === title)
  expect(modal, `应存在「${title}」弹窗`).toBeTruthy()
  return modal
}

describe('OrderDetailView 路由复用', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'PLATFORM_ADMIN'
    http.get.mockReset()
    http.post.mockReset()
    http.post.mockResolvedValue({})
  })

  it('A→B 路由切换后重新加载并展示 B', async () => {
    setRoute('A')
    http.get.mockImplementation((url) => {
      if (url === '/employees') return Promise.resolve([])
      if (url === '/faults/A') return Promise.resolve(makeOrder('A'))
      if (url === '/faults/B') return Promise.resolve(makeOrder('B'))
      return new Promise(() => {})
    })
    const w = mount(OrderDetailView)
    await flushPromises()
    expect(w.text()).toContain('NO-A')
    switchTo('B')
    await flushPromises()
    expect(http.get).toHaveBeenCalledWith('/faults/B')
    expect(w.text()).toContain('NO-B')
    expect(w.text()).not.toContain('NO-A')
  })

  it('A 迟到响应不能覆盖 B', async () => {
    const a = deferred()
    const b = deferred()
    setRoute('A')
    http.get.mockImplementation((url) => {
      if (url === '/employees') return Promise.resolve([])
      if (url === '/faults/A') return a.promise
      if (url === '/faults/B') return b.promise
      return new Promise(() => {})
    })
    const w = mount(OrderDetailView)
    await flushPromises()
    switchTo('B')
    await flushPromises()
    b.resolve(makeOrder('B'))
    await flushPromises()
    expect(w.text()).toContain('NO-B')
    a.resolve(makeOrder('A'))
    await flushPromises()
    expect(w.text()).toContain('NO-B')
    expect(w.text()).not.toContain('NO-A')
  })

  it('A 迟到失败不能覆盖 B 或写入错误态', async () => {
    const a = deferred()
    setRoute('A')
    http.get.mockImplementation((url) => {
      if (url === '/employees') return Promise.resolve([])
      if (url === '/faults/A') return a.promise
      if (url === '/faults/B') return Promise.resolve(makeOrder('B'))
      return new Promise(() => {})
    })
    const w = mount(OrderDetailView)
    await flushPromises()
    switchTo('B')
    await flushPromises()
    expect(w.text()).toContain('NO-B')
    a.reject(new Error('A 请求失败'))
    await flushPromises()
    expect(w.text()).toContain('NO-B')
    expect(w.text()).not.toContain('A 请求失败')
  })

  it('切换到 B 后展示的仍是 A 时，派单提交被拒绝且不调用接口', async () => {
    const w = await mountAtA()
    const confirm = await openDispatch(w)
    switchTo('B')
    confirm.element.click()
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('切换到 B 后展示的仍是 A 时，取消提交被拒绝且不调用接口', async () => {
    const w = await mountAtA()
    await w.findAll('button').find((b) => b.text().trim() === '取消').trigger('click')
    await flushPromises()
    const modal = findModal(w, '取消工单')
    await modal.find('textarea').setValue('客户撤销')
    switchTo('B')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('切换到 B 后展示的仍是 A 时，代报修提交被拒绝且不调用接口', async () => {
    const w = await mountAtA()
    await w.findAll('button').find((b) => b.text().trim() === '代报修').trigger('click')
    await flushPromises()
    const modal = findModal(w, '代报修录入')
    await modal.find('input').setValue('REC-1234')
    switchTo('B')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('切换到 B 后展示的仍是 A 时，关闭工单提交被拒绝且不调用接口', async () => {
    const w = await mountAtA({ status: 'PENDING_CONFIRM' })
    await w.findAll('button').find((b) => b.text().trim() === '关闭工单').trigger('click')
    await flushPromises()
    const modal = findModal(w, '关闭工单')
    await modal.find('textarea').setValue('已修复')
    await modal.find('input').setValue('sig-1')
    switchTo('B')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('展示工单与路由一致时按当前 id 提交派单', async () => {
    const w = await mountAtA()
    const confirm = await openDispatch(w)
    await confirm.trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/admin/faults/A/dispatch', { workerId: 'w1', reason: '就近派单' })
  })

  it('展示工单与路由一致时按当前 id 提交关闭', async () => {
    const w = await mountAtA({ status: 'PENDING_CONFIRM' })
    await w.findAll('button').find((b) => b.text().trim() === '关闭工单').trigger('click')
    await flushPromises()
    const modal = findModal(w, '关闭工单')
    await modal.find('textarea').setValue('已修复')
    await modal.find('input').setValue('sig-1')
    modal.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/faults/A/close', { result: '已修复', signature: 'sig-1' })
  })

  it('展示工单与路由一致时按当前 id 提交取消与代报修', async () => {
    const w = await mountAtA()
    await w.findAll('button').find((b) => b.text().trim() === '取消').trigger('click')
    await flushPromises()
    const cancel = findModal(w, '取消工单')
    await cancel.find('textarea').setValue('客户撤销')
    cancel.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/faults/A/cancel', { reason: '客户撤销' })

    http.post.mockClear()
    await w.findAll('button').find((b) => b.text().trim() === '代报修').trigger('click')
    await flushPromises()
    const proxy = findModal(w, '代报修录入')
    await proxy.find('input').setValue('REC-1234')
    proxy.vm.$emit('confirm')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/faults/A/proxy', { proxySource: 'PHONE', recordingNo: 'REC-1234' })
  })
})
