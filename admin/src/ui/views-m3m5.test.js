import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

// D-02：M3–M5 业务视图级测试（mock API，验证渲染与关键交互）
vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  }
}))

import OrgView from '../views/OrgView.vue'
import GrantsView from '../views/GrantsView.vue'
import RecordsView from '../views/RecordsView.vue'
import SettingsView from '../views/SettingsView.vue'
import StatsView from '../views/StatsView.vue'
import AuditView from '../views/AuditView.vue'

describe('M3-M5 business views render', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('OrgView renders heading + table', async () => {
    const w = mount(OrgView)
    await flushPromises()
    expect(w.text()).toContain('组织架构')
  })
  it('GrantsView renders heading', async () => {
    const w = mount(GrantsView)
    await flushPromises()
    expect(w.text()).toContain('授权管理')
  })
  it('RecordsView renders heading', async () => {
    const w = mount(RecordsView)
    await flushPromises()
    expect(w.text()).toContain('记录单复核')
  })
  it('SettingsView renders config fields', async () => {
    const w = mount(SettingsView)
    await flushPromises()
    expect(w.text()).toContain('系统配置')
  })
  it('StatsView renders KPI cards', async () => {
    const w = mount(StatsView)
    await flushPromises()
    expect(w.findAll('.kpi-card').length).toBe(3)
  })
  it('AuditView renders read-only heading', async () => {
    const w = mount(AuditView)
    await flushPromises()
    expect(w.text()).toContain('审计日志')
  })
})