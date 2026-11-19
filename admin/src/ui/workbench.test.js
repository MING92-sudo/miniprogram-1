import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import { useAuthStore } from '../stores/auth'

vi.mock('../api/admin', () => ({
  dashboard: vi.fn()
}))
import { dashboard } from '../api/admin'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div/>' }, meta: { title: '概览工作台', group: '概览' } },
  { path: '/contracts', component: { template: '<div/>' } },
  { path: '/dispatch', component: { template: '<div/>' } },
  { path: '/handovers', component: { template: '<div/>' } },
  { path: '/records', component: { template: '<div/>' } },
  { path: '/projects', component: { template: '<div/>' } },
  { path: '/elevators', component: { template: '<div/>' } },
  { path: '/plans', component: { template: '<div/>' } },
  { path: '/reports/failed', component: { template: '<div/>' } },
  { path: '/register/workers', component: { template: '<div/>' } }
] })

const payload = {
  orders: { dueToday: 3, dueSoon: 4, overdue: 1, inProgress: 2, elevatorTotal: 12 },
  reports: { failed: 2, reported: 5, submitted: 2, unconfirmed: 3, openFaults: 1 },
  platform: { employeeTotal: 8, employeePendingSync: 2, elevatorGeoMissing: 1, lastSyncAt: '2026-11-19 10:00', platformConfigured: true },
  trend: [{ date: '11-19', completed: 2 }],
  warnCount: 4
}

describe('Workbench（数据来自 GET /admin/dashboard）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'PLATFORM_ADMIN'
    dashboard.mockReset()
    router.push('/dashboard')
  })

  it('renders 7 KPI cards', async () => {
    dashboard.mockResolvedValue(payload)
    await router.isReady()
    const w = mount(DashboardView, { global: { plugins: [router] } })
    await flushPromises()
    expect(w.findAll('.kpi').length).toBe(7)
  })

  it('renders real KPI numbers from the API', async () => {
    dashboard.mockResolvedValue(payload)
    await router.isReady()
    const w = mount(DashboardView, { global: { plugins: [router] } })
    await flushPromises()
    const elevKpi = w.findAll('.kpi')[0]
    expect(elevKpi.find('.kpi-n').text()).toContain('12')
    expect(w.text()).toContain('上报失败')
  })

  it('shows alert strips when numbers are non-zero', async () => {
    dashboard.mockResolvedValue(payload)
    await router.isReady()
    const w = mount(DashboardView, { global: { plugins: [router] } })
    await flushPromises()
    expect(w.findAll('.alert-strip').length).toBeGreaterThan(0)
    expect(w.text()).toContain('SLA 超时')
  })

  it('falls back to zeros and no alerts when the API fails', async () => {
    dashboard.mockRejectedValue(new Error('boom'))
    await router.isReady()
    const w = mount(DashboardView, { global: { plugins: [router] } })
    await flushPromises()
    expect(w.text()).toContain('boom')
    expect(w.findAll('.kpi').length).toBe(0)
  })
})
