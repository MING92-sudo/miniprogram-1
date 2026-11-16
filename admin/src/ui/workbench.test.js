import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import { usePerspectiveStore } from './stores/perspective'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div/>' }, meta: { title: '概览工作台', group: '概览' } },
  { path: '/contracts', component: { template: '<div/>' } },
  { path: '/dispatch', component: { template: '<div/>' } },
  { path: '/handovers', component: { template: '<div/>' } },
  { path: '/records', component: { template: '<div/>' } },
  { path: '/projects', component: { template: '<div/>' } },
  { path: '/elevators', component: { template: '<div/>' } }
] })

describe('Workbench（原型 Views.dashboard 对齐）', () => {
  beforeEach(() => { setActivePinia(createPinia()); router.push('/dashboard') })
  it('renders 7 prototype KPI cards', async () => {
    await router.isReady()
    const w = mount(DashboardView, { global: { plugins: [router] } })
    expect(w.findAll('.kpi').length).toBe(7)
  })
  it('KPI elevators total is 20 for ALL scope', async () => {
    await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['PLATFORM_ADMIN']); p.setRole('PLATFORM_ADMIN')
    const w = mount(DashboardView, { global: { plugins: [router] } })
    expect(w.text()).toContain('20')
  })
  it('renders alert strips and todo table with permission codes', async () => {
    await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['PLATFORM_ADMIN']); p.setRole('PLATFORM_ADMIN')
    const w = mount(DashboardView, { global: { plugins: [router] } })
    expect(w.find('.alert-strip').exists()).toBe(true)
    expect(w.text()).toContain('我的待办')
    expect(w.text()).toContain('handover:review')
  })
  it('scope switch changes KPI totals (MAINT_SUP 14 elevators)', async () => {
    await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['PLATFORM_ADMIN', 'MAINT_SUP']); p.setRole('MAINT_SUP')
    const w = mount(DashboardView, { global: { plugins: [router] } })
    const elevKpi = w.findAll('.kpi')[1]
    expect(elevKpi.find('.kpi-n').text()).toContain('14')
  })
})