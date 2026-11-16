import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// D-02：M3–M5 业务视图级测试（mock API，验证渲染与关键交互）
vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))

import OrgView from '../views/OrgView.vue'
import GrantsView from '../views/GrantsView.vue'
import RecordsView from '../views/RecordsView.vue'
import SettingsView from '../views/SettingsView.vue'
import StatsView from '../views/StatsView.vue'
import AuditView from '../views/AuditView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/org', component: { template: '<div/>' } },
  { path: '/grants', component: { template: '<div/>' } },
  { path: '/records', component: { template: '<div/>' } },
  { path: '/settings', component: { template: '<div/>' } },
  { path: '/stats', component: { template: '<div/>' } },
  { path: '/audit', component: { template: '<div/>' } }
] })
function mountView(V) { return mount(V, { global: { plugins: [router] } }) }

describe('M3-M5 business views render（原型对齐版）', () => {
  beforeEach(() => { setActivePinia(createPinia()); router.push('/') })
  it('OrgView renders heading + tree', async () => {
    const w = mountView(OrgView)
    await flushPromises()
    expect(w.text()).toContain('组织架构')
  })
  it('GrantsView renders heading', async () => {
    const w = mountView(GrantsView)
    await flushPromises()
    expect(w.text()).toContain('授权管理')
  })
  it('RecordsView renders heading', async () => {
    const w = mountView(RecordsView)
    await flushPromises()
    expect(w.text()).toContain('记录单复核')
  })
  it('SettingsView renders config fields', async () => {
    const w = mountView(SettingsView)
    await flushPromises()
    expect(w.text()).toContain('系统配置')
  })
  it('StatsView renders 7 prototype KPI cards', async () => {
    const w = mountView(StatsView)
    await flushPromises()
    expect(w.findAll('.kpi').length).toBe(7)
  })
  it('AuditView renders read-only notice', async () => {
    const w = mountView(AuditView)
    await flushPromises()
    expect(w.text()).toContain('审计日志')
    expect(w.text()).toContain('只读')
  })
})