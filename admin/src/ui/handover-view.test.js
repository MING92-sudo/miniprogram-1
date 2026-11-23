import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('./permission-v2', () => ({
  state: () => 'Y',
  stateForRoles: () => 'Y',
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
  getHandoverOptions: vi.fn(),
  createHandover: vi.fn(),
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
import http from '../api/client'
import * as api from '../api/handover'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/handovers', component: { template: '<div/>' } }
] })

const order = { id: 'ho_1', handoverNo: 'HO1', elevatorCode: 'EL1', projectName: '渝北项目', status: 'INSPECTING', installUnitName: '某安装公司' }
const item = { id: 'hi_1', itemNo: 1, itemGroup: 'G01', itemName: '机器空间通道', requirement: '通道畅通', important: false, result: null }

const orderA = { id: 'ho_a', handoverNo: 'HOA', elevatorCode: 'ELA', projectName: '项目A', status: 'INSPECTING', installUnitName: '安装A' }
const orderB = { id: 'ho_b', handoverNo: 'HOB', elevatorCode: 'ELB', projectName: '项目B', status: 'INSPECTING', installUnitName: '安装B' }
const itemA = { id: 'hi_a', itemNo: 1, itemGroup: 'G01', itemName: 'A项检查', requirement: '', important: false, result: null }
const itemB = { id: 'hi_b', itemNo: 2, itemGroup: 'G02', itemName: 'B项检查', requirement: '', important: false, result: null }

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
function btn(w, label) {
  return w.findAll('button').find((b) => b.text() === label)
}
function rowByNo(w, no) {
  return w.findAll('tbody tr').find((tr) => tr.text().includes(no))
}
function mountView() {
  return mount(HandoversView, { global: { plugins: [router] } })
}

describe('HandoversView C-12（原型对齐版）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.listHandovers.mockResolvedValue({ list: [order], total: 1 })
    api.getHandoverOptions.mockResolvedValue({ useUnits: [{ id: 'uu_1', unitName: '世纪物业' }] })
    api.createHandover.mockResolvedValue({ id: 'ho_new', status: 'PENDING_INST_REVIEW' })
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

  it('create requires three documents and submits unified elevator fields without elevatorId', async () => {
    const w = mountView()
    await flushPromises()
    await btn(w, '移交单起草').trigger('click')
    await flushPromises()

    expect(w.text()).not.toContain('关联电梯')
    expect(w.text()).toContain('监督检验报告')
    expect(w.text()).toContain('安装质量证明文件')
    expect(w.text()).toContain('维护使用说明书')
    expect(api.getHandoverOptions).toHaveBeenCalled()

    Object.assign(w.vm.createModel, {
      projectName: '渝北项目', useUnitId: 'uu_1', factoryNumber: 'SGL-2026-001', insideNumber: 'KT-01',
      elevatorCode: '', regCode: '', deviceCode: ''
    })
    await btn(w, '确认起草').trigger('click')
    await flushPromises()
    expect(api.createHandover).not.toHaveBeenCalled()

    w.vm.createDocs[0].fileId = 'file_supervision'
    w.vm.createDocs[1].fileId = 'file_quality'
    w.vm.createDocs[2].fileId = 'file_manual'
    await btn(w, '确认起草').trigger('click')
    await flushPromises()

    expect(api.createHandover).toHaveBeenCalledTimes(1)
    const body = api.createHandover.mock.calls[0][0]
    expect(body).toMatchObject({
      projectName: '渝北项目', useUnitId: 'uu_1', factoryNumber: 'SGL-2026-001', insideNumber: 'KT-01',
      supervisionReportFileId: 'file_supervision', qualityCertificateFileId: 'file_quality',
      maintenanceManualFileId: 'file_manual'
    })
    expect(body).not.toHaveProperty('elevatorId')
  })

  it('slowACannotOverwriteBDetail', async () => {
    api.listHandovers.mockResolvedValue({ list: [orderA, orderB], total: 2 })
    const dDetailA = deferred(); const dDetailB = deferred()
    const dCheckA = deferred(); const dCheckB = deferred()
    const dRectA = deferred(); const dRectB = deferred()
    api.handoverDetail.mockImplementation((id) => (id === 'ho_a' ? dDetailA.promise : dDetailB.promise))
    api.getChecklist.mockImplementation((id) => (id === 'ho_a' ? dCheckA.promise : dCheckB.promise))
    api.getRectifications.mockImplementation((id) => (id === 'ho_a' ? dRectA.promise : dRectB.promise))

    const w = mountView()
    await flushPromises()
    await rowByNo(w, 'HOA').trigger('click')
    await rowByNo(w, 'HOB').trigger('click')
    await flushPromises()

    dDetailB.resolve({ id: 'ho_b', handoverNo: 'HOB', projectName: '项目B', elevatorCode: 'ELB', status: 'INSPECTING', signs: [], checks: [] })
    dCheckB.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemB] })
    dRectB.resolve([{ id: 'rb_1', itemNo: 2 }])
    await flushPromises()
    expect(w.find('.drawer').text()).toContain('B项检查')

    dDetailA.resolve({ id: 'ho_a', handoverNo: 'HOA', projectName: '项目A', elevatorCode: 'ELA', status: 'INSPECTING', signs: [], checks: [] })
    dCheckA.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemA] })
    dRectA.resolve([{ id: 'ra_1', itemNo: 1 }])
    await flushPromises()

    const drawer = w.find('.drawer').text()
    expect(drawer).toContain('项目B')
    expect(drawer).toContain('B项检查')
    expect(drawer).not.toContain('项目A')
    expect(drawer).not.toContain('A项检查')
    expect(w.vm.detail.id).toBe('ho_b')
    expect(w.vm.items.map((i) => i.id)).toEqual(['hi_b'])
    expect(w.vm.rects.map((r) => r.id)).toEqual(['rb_1'])
  })

  it('switching order clears per-order sub-resources and active tab', async () => {
    api.listHandovers.mockResolvedValue({ list: [orderA, orderB], total: 2 })
    const dDetailB = deferred(); const dCheckB = deferred(); const dRectB = deferred()
    api.handoverDetail.mockImplementation((id) => (id === 'ho_b'
      ? dDetailB.promise
      : Promise.resolve({ id: 'ho_a', handoverNo: 'HOA', projectName: '项目A', elevatorCode: 'ELA', status: 'INSPECTING', signs: [{ nodeType: 'SITE_INSPECT', action: 'APPROVE', byName: '签A' }], checks: [{ id: 'c_a', checkerName: '查A', checkedAt: '2026-11-20 10:00:00' }] })))
    api.getChecklist.mockImplementation((id) => (id === 'ho_b'
      ? dCheckB.promise
      : Promise.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemA] })))
    api.getRectifications.mockImplementation((id) => (id === 'ho_b'
      ? dRectB.promise
      : Promise.resolve([{ id: 'ra_1', itemNo: 1 }])))
    api.getArchive.mockResolvedValue([{ id: 'ha_1', seq: 1, category: 'CHECKLIST', sha256: 'shaA', archivedAt: '2026-11-14 10:00:00' }])

    const w = mountView()
    await flushPromises()
    await rowByNo(w, 'HOA').trigger('click')
    await flushPromises()
    expect(w.vm.items.map((i) => i.id)).toEqual(['hi_a'])
    expect(w.vm.signs.length).toBe(1)
    expect(w.vm.checks.length).toBe(1)
    expect(w.vm.rects.length).toBe(1)

    await btn(w, '一梯一档').trigger('click')
    await flushPromises()
    expect(w.vm.archive.length).toBe(1)
    expect(w.vm.tab).toBe('archive')
    w.vm.conditional.siteLimited = true
    w.vm.conditional.tripartiteAgreed = true
    w.vm.conditional.controlMeasures = '隔离护栏'

    await rowByNo(w, 'HOB').trigger('click')
    expect(w.vm.tab).toBe('checklist')
    expect(w.vm.items).toEqual([])
    expect(w.vm.rects).toEqual([])
    expect(w.vm.signs).toEqual([])
    expect(w.vm.checks).toEqual([])
    expect(w.vm.archive).toEqual([])
    expect(w.vm.check).toEqual({})
    expect(w.vm.conditional).toMatchObject({ siteLimited: false, tripartiteAgreed: false, controlMeasures: '' })
    expect(w.vm.loaded).toBe(false)
    expect(w.vm.detail.id).toBe('ho_b')
    const drawer = w.find('.drawer').text()
    expect(drawer).not.toContain('A项检查')
    expect(drawer).not.toContain('shaA')
    const onChip = w.findAll('.chip').find((c) => c.classes('on'))
    expect(onChip.text()).toBe('74 项检查清单')
    const conditionalBoxes = w.findAll('.drawer input[type="checkbox"]')
    expect(conditionalBoxes.length).toBe(2)
    expect(conditionalBoxes.every((c) => c.element.checked === false)).toBe(true)
  })

  it('oldItemsCannotSubmitToB', async () => {
    api.listHandovers.mockResolvedValue({ list: [orderA, orderB], total: 2 })
    const dDetailB = deferred(); const dCheckB = deferred(); const dRectB = deferred()
    api.handoverDetail.mockImplementation((id) => (id === 'ho_b'
      ? dDetailB.promise
      : Promise.resolve({ id: 'ho_a', handoverNo: 'HOA', projectName: '项目A', status: 'INSPECTING', signs: [], checks: [] })))
    api.getChecklist.mockImplementation((id) => (id === 'ho_b'
      ? dCheckB.promise
      : Promise.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemA] })))
    api.getRectifications.mockImplementation((id) => (id === 'ho_b' ? dRectB.promise : Promise.resolve([])))

    const w = mountView()
    await flushPromises()
    await rowByNo(w, 'HOA').trigger('click')
    await flushPromises()
    expect(w.find('.drawer').text()).toContain('A项检查')

    await rowByNo(w, 'HOB').trigger('click')
    await flushPromises()
    expect(w.vm.items).toEqual([])
    await btn(w, '提交查验').trigger('click')
    await flushPromises()
    expect(api.submitChecklist).not.toHaveBeenCalled()

    dDetailB.resolve({ id: 'ho_b', handoverNo: 'HOB', projectName: '项目B', status: 'INSPECTING', signs: [], checks: [] })
    dCheckB.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemB] })
    dRectB.resolve([])
    await flushPromises()
    expect(w.find('.drawer').text()).toContain('B项检查')

    await w.find('select[aria-label="第2项结果"]').setValue('PASS')
    await btn(w, '提交查验').trigger('click')
    await flushPromises()
    expect(api.submitChecklist).toHaveBeenCalledTimes(1)
    const [id, body] = api.submitChecklist.mock.calls[0]
    expect(id).toBe('ho_b')
    expect(body.items.map((i) => i.itemNo)).toEqual([2])
    expect(body.items[0].result).toBe('PASS')
  })

  it('late A checklist/rects/archive responses cannot populate B', async () => {
    api.listHandovers.mockResolvedValue({ list: [orderA, orderB], total: 2 })
    const dCheckA = deferred(); const dRectA = deferred(); const dArchA = deferred()
    api.handoverDetail.mockImplementation((id) => Promise.resolve({ id, handoverNo: id === 'ho_a' ? 'HOA' : 'HOB', projectName: id === 'ho_a' ? '项目A' : '项目B', status: 'INSPECTING', signs: [], checks: [] }))
    api.getChecklist.mockImplementation((id) => (id === 'ho_a'
      ? dCheckA.promise
      : Promise.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemB] })))
    api.getRectifications.mockImplementation((id) => (id === 'ho_a' ? dRectA.promise : Promise.resolve([{ id: 'rb_1', itemNo: 2 }])))
    api.getArchive.mockImplementation((id) => (id === 'ho_a' ? dArchA.promise : Promise.resolve([])))

    const w = mountView()
    await flushPromises()
    await rowByNo(w, 'HOA').trigger('click')
    await flushPromises()
    expect(w.vm.detail.id).toBe('ho_a')
    await btn(w, '一梯一档').trigger('click')
    await flushPromises()

    await rowByNo(w, 'HOB').trigger('click')
    await flushPromises()
    expect(w.find('.drawer').text()).toContain('B项检查')
    expect(w.vm.rects.map((r) => r.id)).toEqual(['rb_1'])

    dCheckA.resolve({ check: { totalItems: 74, conclusion: null }, items: [itemA] })
    dRectA.resolve([{ id: 'ra_1', itemNo: 1 }])
    dArchA.resolve([{ id: 'ha_a', seq: 1, category: 'INDEX', sha256: 'shaA', archivedAt: '2026-11-14 10:00:00' }])
    await flushPromises()

    expect(w.vm.items.map((i) => i.id)).toEqual(['hi_b'])
    expect(w.vm.rects.map((r) => r.id)).toEqual(['rb_1'])
    expect(w.vm.archive).toEqual([])
    const drawer = w.find('.drawer').text()
    expect(drawer).toContain('B项检查')
    expect(drawer).not.toContain('A项检查')
    expect(drawer).not.toContain('shaA')
  })

  it('signNode rejects inconsistent target before posting', async () => {
    const w = mountView()
    await flushPromises()
    w.vm.detailId = 'ho_a'
    w.vm.detail = { id: 'ho_b', status: 'PENDING_CONFIRM' }

    await w.vm.signNode('INSTALLER_HANDOVER')

    expect(http.post).not.toHaveBeenCalled()
  })

  it('late sign response cannot refresh another order', async () => {
    api.listHandovers.mockResolvedValue({ list: [orderA, orderB], total: 2 })
    const signing = deferred()
    http.post.mockImplementation((url) => url === '/handovers/ho_a/sign'
      ? signing.promise
      : Promise.resolve({}))
    const w = mountView()
    await flushPromises()
    await rowByNo(w, 'HOA').trigger('click')
    await flushPromises()
    const pending = w.vm.signNode('INSTALLER_HANDOVER')
    await rowByNo(w, 'HOB').trigger('click')
    await flushPromises()
    const detailCallsBeforeLateResponse = api.handoverDetail.mock.calls.length

    signing.resolve({})
    await pending
    await flushPromises()

    expect(api.handoverDetail.mock.calls.length).toBe(detailCallsBeforeLateResponse)
    expect(w.vm.detail.id).toBe('ho_b')
  })
})