import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/admin', () => ({ records: vi.fn() }))

import { records } from '../api/admin'
import SyncView from './SyncView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }]
})

const backendRows = [
  { id: 'ur_s', originalRecordId: 'REC-S', elevatorCode: 'EM-S', reportStatus: 'REPORTED', uploadStatus: 'SUCCESS', retryCount: 0, createdAt: '2026-11-20 10:00:00' },
  { id: 'ur_f', originalRecordId: 'REC-F', elevatorCode: 'EM-F', reportStatus: 'FAILED', uploadStatus: 'FAILED', retryCount: 2, createdAt: '2026-11-19 10:00:00' },
  { id: 'ur_p', originalRecordId: 'REC-P', elevatorCode: 'EM-P', reportStatus: 'PENDING', uploadStatus: 'PENDING', retryCount: 0, createdAt: '2026-11-18 10:00:00' },
  { id: 'ur_u', originalRecordId: 'REC-U', elevatorCode: 'EM-U', reportStatus: 'UNKNOWN', uploadStatus: 'UNKNOWN', retryCount: 0, createdAt: '2026-11-17 10:00:00' }
]

function mountView() {
  return mount(SyncView, { global: { plugins: [router] } })
}

function rowByCode(w, code) {
  return w.findAll('tbody tr').find((tr) => tr.text().includes(code))
}

describe('SyncView 上报状态（后端码）展示', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    router.push('/')
    records.mockResolvedValue({ list: backendRows, total: backendRows.length })
  })

  it('KPI 按后端码分别计数 SUCCESS / FAILED / PENDING / UNKNOWN + 总数', async () => {
    const w = mountView()
    await flushPromises()
    const kpi = (label) => w.findAll('.kpi').find((k) => k.find('.kpi-k').text().includes(label))
    expect(kpi('上报成功').find('.kpi-n').text()).toBe('1')
    expect(kpi('上报异常').find('.kpi-n').text()).toBe('1')
    expect(kpi('待上报').find('.kpi-n').text()).toBe('1')
    expect(kpi('上报待核对').find('.kpi-n').text()).toBe('1')
    expect(kpi('记录总数').find('.kpi-n').text()).toBe('4')
  })

  it('列表展示中文状态标签，UNKNOWN 显示「上报待核对」而非裸码', async () => {
    const w = mountView()
    await flushPromises()
    const tbody = w.find('tbody').text()
    expect(tbody).toContain('上报成功')
    expect(tbody).toContain('上报异常')
    expect(tbody).toContain('待上报')
    expect(tbody).toContain('上报待核对')
    expect(tbody).not.toContain('UNKNOWN')
    expect(tbody).not.toContain('SUCCESS')
  })

  it('状态 tag 语义色按后端码映射', async () => {
    const w = mountView()
    await flushPromises()
    const tone = (code) => rowByCode(w, code).find('.tag').classes()
    expect(tone('REC-S')).toContain('tag-ok')
    expect(tone('REC-F')).toContain('tag-signal')
    expect(tone('REC-P')).toContain('tag-amber')
    expect(tone('REC-U')).toContain('tag-ghost')
  })

  it('筛选选项携带后端码值 + 中文标签', async () => {
    const w = mountView()
    await flushPromises()
    const statusSelect = w.findAll('select').find((s) => s.findAll('option').some((o) => o.element.value === 'SUCCESS'))
    const options = statusSelect.findAll('option').map((o) => ({ value: o.element.value, label: o.text() }))
    expect(options).toEqual([
      { value: '', label: '全部' },
      { value: 'SUCCESS', label: '上报成功' },
      { value: 'FAILED', label: '上报异常' },
      { value: 'PENDING', label: '待上报' },
      { value: 'UNKNOWN', label: '上报待核对' }
    ])
  })

  it('详情抽屉与列表使用同一上报状态文案', async () => {
    const w = mountView()
    await flushPromises()
    await rowByCode(w, 'REC-P').find('button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('待上报')
    expect(w.text()).not.toContain('待执行')
  })

  it('UNKNOWN 行只读，无人工重报入口，抽屉同时显示中文标签与后端码', async () => {
    const w = mountView()
    await flushPromises()
    const unknownRow = rowByCode(w, 'REC-U')
    const actionText = unknownRow.findAll('button').map((b) => b.text()).join('|')
    expect(actionText).not.toContain('重报')
    expect(actionText).toContain('详情')
    await unknownRow.find('button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('上报待核对')
    expect(w.text()).toContain('UNKNOWN')
  })
})
