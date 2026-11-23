import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
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
import { exportCsv, selectExportRows } from './export-csv'
import DispatchView from '../views/DispatchView.vue'
import ProjectsView from '../views/ProjectsView.vue'
import AuditView from '../views/AuditView.vue'
import ElevatorsView from '../views/ElevatorsView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: { template: '<div/>' } },
    { path: '/dispatch', component: { template: '<div/>' } },
    { path: '/projects', component: { template: '<div/>' } },
    { path: '/audit', component: { template: '<div/>' } },
    { path: '/elevators', component: { template: '<div/>' } },
    { path: '/order/:id', component: { template: '<div/>' } },
    { path: '/elevator/:id', component: { template: '<div/>' } }
  ]
})

// 捕获 exportCsv 写入 Blob 的 CSV 文本（同文件既有用例的 Blob 覆盖思路）
async function captureCsv(fn) {
  return await new Promise((resolve) => {
    const OriginalBlob = globalThis.Blob
    globalThis.Blob = class extends OriginalBlob {
      constructor(parts, options) { super(parts, options); resolve(parts.join('')) }
    }
    try { fn() } finally { globalThis.Blob = OriginalBlob }
  })
}
async function csvOf(filename, columns, rows) {
  return captureCsv(() => exportCsv(filename, columns, rows))
}

async function mountView(View, path) {
  await router.push(path)
  const w = shallowMount(View, { global: { plugins: [router] } })
  await flushPromises()
  return w
}

describe('CSV 导出公式注入防护', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  it('中和所有公式起始字符并保留正常 CSV 转义', async () => {
    const blob = await new Promise((resolve) => {
      const OriginalBlob = globalThis.Blob
      globalThis.Blob = class extends OriginalBlob {
        constructor(parts, options) { super(parts, options); resolve(this) }
      }
      exportCsv('测试', [{ key: 'v', label: '值' }], [
        { v: '=1+1' }, { v: '+SUM(A1:A2)' }, { v: '-2' }, { v: '@cmd' }, { v: '普通文本' }
      ])
      globalThis.Blob = OriginalBlob
    })
    const text = await blob.text()
    expect(text).toContain("'=1+1")
    expect(text).toContain("'+SUM(A1:A2)")
    expect(text).toContain("'-2")
    expect(text).toContain("'@cmd")
    expect(text).toContain('普通文本')
  })
})

describe('导出列 value 映射与选中行导出', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    http.get.mockReset()
    http.get.mockResolvedValue([])
  })

  it('exportCsv 使用 column.value(row) 生成单元格', async () => {
    const text = await csvOf('测试', [{ key: 'raw', label: '展示', value: (r) => '标签:' + r.raw }], [{ raw: 'A' }])
    expect(text).toContain('标签:A')
    expect(text).not.toContain(',A')
  })

  it('selectExportRows 只保留选中 id；无 ids 时全量', () => {
    const all = Array.from({ length: 100 }, (_, i) => ({ id: i + 1 }))
    expect(selectExportRows(all, [50])).toEqual([{ id: 50 }])
    expect(selectExportRows(all, [])).toEqual(all)
    expect(selectExportRows(all)).toEqual(all)
  })

  it('勾选 1/100 台只导出 1 条', async () => {
    const all = Array.from({ length: 100 }, (_, i) => ({ id: i + 1, code: 'E' + (i + 1) }))
    const picked = selectExportRows(all, [50])
    expect(picked.length).toBe(1)
    const text = await csvOf('电梯', [{ key: 'code', label: '编码' }], picked)
    expect(text).toContain('E50')
    expect(text).not.toContain('E49')
  })

  it('DispatchView 虚拟列输出表格展示值', async () => {
    const w = await mountView(DispatchView, '/dispatch')
    const row = {
      id: 'f1', faultNo: 'BWJX20261122001', typeLabel: '急修', levelLabel: 'P1',
      elevatorCode: 'DT001', useUnitName: '世纪大厦', createdByName: '王芳', reporterPhone: '13800000001',
      status: 'OPEN', slaMinutes: 30, remaining: 10, overtime: false,
      dispatchedWorkerName: '张伟', sourceLabel: '电话代报修'
    }
    const text = await csvOf('工单', w.vm.cols, [row])
    expect(text).toContain('急修 P1')
    expect(text).toContain('世纪大厦')
    expect(text).toContain('王芳 / 13800000001')
    expect(text).toContain('待接单')
    expect(text).toContain('30′ / 剩 10′')
    expect(text).toContain('张伟')
    expect(text).toContain('电话代报修')
    w.unmount()
  })

  it('ElevatorsView 批量导出仅导出选中行，工具栏无 ids 时全量', async () => {
    const w = await mountView(ElevatorsView, '/elevators')
    w.vm.rows = [
      { id: 'e1', elevatorCode: 'E1', projectName: 'A' },
      { id: 'e2', elevatorCode: 'E2', projectName: 'B' }
    ]
    const selected = await captureCsv(() => w.vm.exportElev({ action: 'elevator:export', ids: ['e2'] }))
    expect(selected).toContain('E2')
    expect(selected).not.toContain('E1')
    const all = await captureCsv(() => w.vm.exportElev())
    expect(all).toContain('E1')
    expect(all).toContain('E2')
    w.unmount()
  })

  it('ProjectsView 使用单位与维保项目部虚拟列输出名称', async () => {
    const w = await mountView(ProjectsView, '/projects')
    const row = {
      id: 'p1', projectNo: 'P001', name: '世纪项目', useUnitName: '世纪物业', maintOrgName: '维保一队',
      count: 12, contract: 'C001', status: 'ACTIVE', last: '2026-11-20'
    }
    const text = await csvOf('项目', w.vm.cols, [row])
    expect(text).toContain('世纪物业')
    expect(text).toContain('维保一队')
    w.unmount()
  })

  it('AuditView 时间/角色/动作/范围/结果/哈希按展示值导出', async () => {
    const w = await mountView(AuditView, '/audit')
    const row = {
      id: 'a1', createdAt: '2026-11-22T00:30:00', actorId: 'u1', actorRole: 'MAINT_SUP',
      action: 'order:dispatch', target: 'WO-1', projectId: 'proj_1', orgId: '',
      ip: '10.0.0.1', result: 'SUCCESS', hashRef: 'abcdef123456'
    }
    const text = await csvOf('审计', w.vm.cols, [row])
    expect(text).toContain('2026-11-22 00:30')
    expect(text).toContain('维保主管')
    expect(text).toContain('派单')
    expect(text).toContain('proj_1')
    expect(text).toContain('成功')
    expect(text).toContain('abcdef12')
    expect(text).not.toContain('abcdef123456')
    w.unmount()
  })
})
