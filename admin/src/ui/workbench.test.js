import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import DashboardView from '../views/DashboardView.vue'
import { usePerspectiveStore } from './stores/perspective'

describe('Workbench', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('renders 7 KPI cards', () => {
    const w = mount(DashboardView)
    expect(w.findAll('.kpi-card').length).toBe(7)
  })
  it('KPI elevators total is 20 for ALL scope', () => {
    const p = usePerspectiveStore(); p.setRoleSet(['PLATFORM_ADMIN']); p.setRole('PLATFORM_ADMIN')
    const w = mount(DashboardView)
    expect(w.text()).toContain('20')
  })
  it('perspective switcher lists roleSet', () => {
    const p = usePerspectiveStore(); p.setRoleSet(['MAINT_MGR', 'MAINT_SUP'])
    const w = mount(DashboardView)
    expect(w.findAll('.persp-btn').length).toBe(2)
  })
})