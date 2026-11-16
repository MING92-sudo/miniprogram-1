import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// C-12 移交管理视图级测试：mock API/权限/鉴权，验证渲染与关键交互 wiring。
// 权限三态矩阵本身由 permission-v2.test.js 覆盖，此处 state 恒 Y 以聚焦视图行为。
vi.mock('./permission-v2', () => ({
  state: () => 'Y',
  canonical: (r) => r,
  defaultScope: () => 'ALL'
}))
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ role: 'INST_MGR', user: { id: 'u1', name: '张工' }, userName: '张工', can: () => true })
}))
vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))
vi.mock('../api/upload', () => ({ uploadFile: vi.fn(() => Promise.resolve({ fileId: 'file_1', url: '' })) }))
vi.mock('../api/handover', () => ({
  listHandovers: vi.fn(),
  handoverDetail: vi.fn(),
  confirmHandover: vi.fn(),
  getChecklist: vi.fn(),
  submitChecklist: vi.fn(),
  getRectifications: vi.fn(),
  rectifyItem: vi.fn(),
  recheckItem: vi.fn(),
  naApprove: vi.fn(),
  exportPdf: vi.fn(),
  getArchive: vi.fn()
}))

import HandoversView from '../views/HandoversView.vue'
import * as api from '../api/handover'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/handovers', component: { template: '<div/>' } }
] })

const order = { id: 'ho_1', handoverNo: 'HO1', elevatorCode: 'EL1', projectName: '渝北项目', status: 'INSPECTING', installUnitName: '某安装公司' }
const item = { id: 'hi_1', itemNo: 1, itemGroup: 'G01', itemName: '机器空间通道', requirement: '通道畅通', important: false, result: null }

function btn(w, label) {
  return w.findAll('button').find((b) => b.text() === label)
}
function mountView() {
  return mount(HandoversView, { global: { plugins: [router] } })
}

describe('HandoversView C-12（原型对齐版）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.listHandovers.mockResolvedValue({ list: [order], total: 1 })
    api.handoverDetail.mockResolvedValue({})
    api.getChecklist.mockResolvedValue({ check: { totalItems: 74, conclusion: null }, items: [item] })
    api.getRectifications.mockResolvedValue([])
    api.getArchive.mockResolvedValue([{ id: 'ha_1', seq: 1, category: 'CHECKLIST', sha256: 'abc', archivedAt: '2026-11-14 10:00:00' }])
    api.submitChecklist.mockResolvedValue({ status: 'PENDING_CONFIRM' })
    api.recheckItem.mockResolvedValue({ status: 'PENDING_CONFIRM' })
    api.rectifyItem.mockResolvedValue({})
    api.naApprove.mockResolvedValue({})
    api.exportPdf.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }))
    global.URL.createObjectURL = vi.fn(() => 'blob:mock')
    global.URL.revokeObjectURL = vi.fn()
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('renders heading and list', async () => {
    await router.push('/handovers'); await router.isReady()
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('移交管理')
    expect(w.text()).toContain('HO1')
    expect(api.listHandovers).toHaveBeenCalled()
  })

  it('row click opens drawer and loads checklist + rectifications', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    expect(w.find('.drawer').exists()).toBe(true)
    expect(w.text()).toContain('机器空间通道')
    expect(api.getChecklist).toHaveBeenCalledWith('ho_1')
    expect(api.getRectifications).toHaveBeenCalledWith('ho_1')
  })

  it('submits checklist with item payload', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    await w.find('select[aria-label="第1项结果"]').setValue('PASS')
    await btn(w, '提交查验').trigger('click')
    await flushPromises()
    expect(api.submitChecklist).toHaveBeenCalledTimes(1)
    const [id, body] = api.submitChecklist.mock.calls[0]
    expect(id).toBe('ho_1')
    expect(body.items[0]).toMatchObject({ itemNo: 1, result: 'PASS' })
  })

  it('rectification tab opens recheck modal and calls recheckItem', async () => {
    api.getRectifications.mockResolvedValue([{ id: 'hr_1', itemNo: 2, itemType: 'G01', recheckResult: null }])
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    await btn(w, '1:1 整改清单').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('第 2 项')
    await btn(w, '逐条复验').trigger('click')
    await flushPromises()
    await btn(w, '提交复验').trigger('click')
    await flushPromises()
    expect(api.recheckItem).toHaveBeenCalledWith('ho_1', 'hr_1', expect.objectContaining({ result: 'PASS' }))
  })

  it('NA approval modal calls naApprove', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    await btn(w, '☆项 NA 审批').trigger('click')
    await flushPromises()
    await btn(w, '批准').trigger('click')
    await flushPromises()
    expect(api.naApprove).toHaveBeenCalledWith('ho_1', expect.objectContaining({ approverId: 'u1' }))
  })

  it('archive tab loads and renders directory', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    await btn(w, '一梯一档').trigger('click')
    await flushPromises()
    expect(api.getArchive).toHaveBeenCalledWith('ho_1')
    expect(w.text()).toContain('检查清单')
    expect(w.text()).toContain('abc')
  })

  it('export triggers PDF blob download', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('tbody tr').trigger('click')
    await flushPromises()
    await btn(w, '检查清单').trigger('click')
    await flushPromises()
    expect(api.exportPdf).toHaveBeenCalledWith('ho_1', 'CHECK')
    expect(global.URL.createObjectURL).toHaveBeenCalled()
  })
})