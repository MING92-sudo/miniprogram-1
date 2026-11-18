import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// 权限 state 可被单测改写：默认 Y（有裁定权限），个别用例置非 Y 验证 DENY 不渲染按钮。
const permissionState = vi.fn(() => 'Y')
vi.mock('../ui/permission-v2', () => ({
  state: (...args) => permissionState(...args),
  canonical: (r) => r,
  defaultScope: () => 'ALL',
  allowsNew: () => false,
  canonicalCodes: () => [],
  canonicalRoles: () => []
}))
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ role: 'SYS_ADMIN', user: { id: 'u1' }, can: () => true })
}))
vi.mock('../api/migration', () => ({
  listMigrationIssues: vi.fn(),
  resolveMigrationIssue: vi.fn()
}))

import MigrationIssuesView from './MigrationIssuesView.vue'
import * as api from '../api/migration'
import { useUiStore } from '../ui/stores/ui'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/migration-issues', component: { template: '<div/>' } }
] })

const rows = [
  { id: 'mi_1', issueType: 'NO_ANCHOR', objectType: 'RECORD', objectId: 'r1', elevatorCode: 'EL1',
    currentUnitId: 'u_old', candidateUnitId: 'u_new', evidence: '证据A', confidence: 'HIGH', resolution: 'PENDING' },
  { id: 'mi_2', issueType: 'DEDUP_CONFLICT', objectType: 'FAULT', objectId: 'f1', elevatorCode: 'EL2',
    currentUnitId: 'u_a', candidateUnitId: 'u_b', evidence: '证据B', confidence: 'LOW', resolution: 'PENDING' }
]

function mountView() {
  return mount(MigrationIssuesView, { global: { plugins: [router] } })
}
function btn(w, label) {
  return w.findAll('button').find((b) => b.text() === label)
}

describe('MigrationIssuesView（权限 P2 T8）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    permissionState.mockReturnValue('Y')
    api.listMigrationIssues.mockResolvedValue({ list: rows, total: 2, pending: 7 })
    api.resolveMigrationIssue.mockResolvedValue({ ok: true })
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('渲染清单并展示 pending 计数', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('迁移核对')
    expect(w.findAll('tbody tr').length).toBe(2)
    expect(w.text()).toContain('EL1')
    expect(w.text()).toContain('EL2')
    expect(w.find('.meta-strip').text()).toContain('待核对 7')
    expect(api.listMigrationIssues).toHaveBeenCalledWith(expect.objectContaining({ page: 1 }))
  })

  it('无 config:manage 权限不渲染裁定按钮', async () => {
    permissionState.mockReturnValue('-')
    const w = mountView()
    await flushPromises()
    expect(w.findAll('tbody tr').length).toBe(2)
    expect(btn(w, '裁定')).toBeUndefined()
  })

  it('裁定提交调用 resolveMigrationIssue 并刷新', async () => {
    const w = mountView()
    await flushPromises()
    await btn(w, '裁定').trigger('click')
    await flushPromises()

    // BACKFILLED 缺 backfillUnitId → 前端拦截不发请求
    await btn(w, '提交裁定').trigger('click')
    await flushPromises()
    expect(api.resolveMigrationIssue).not.toHaveBeenCalled()
    expect(useUiStore().toasts.some((t) => t.msg.includes('回填单位'))).toBe(true)

    // 补齐必填项后提交 → 调用 resolve 且列表刷新
    await w.find('input[placeholder="请输入回填单位 ID"]').setValue('u_fill')
    await w.find('textarea[placeholder="请填写裁定说明（必填）"]').setValue('人工核对通过')
    await btn(w, '提交裁定').trigger('click')
    await flushPromises()
    expect(api.resolveMigrationIssue).toHaveBeenCalledWith('mi_1', expect.objectContaining({
      resolution: 'BACKFILLED', backfillUnitId: 'u_fill', note: '人工核对通过'
    }))
    expect(api.listMigrationIssues).toHaveBeenCalledTimes(2)
    expect(btn(w, '提交裁定')).toBeUndefined()
  })
})
