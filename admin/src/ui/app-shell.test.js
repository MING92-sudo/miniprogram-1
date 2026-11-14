import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import AppShell from './AppShell.vue'
import { usePerspectiveStore } from './stores/perspective'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div>workbench</div>' }, meta: { title: '工作台', group: '概览' } }
] })

describe('AppShell', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('renders sidebar groups and scope indicator', async () => {
    router.push('/dashboard'); await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['MAINT_MGR']); p.setRole('MAINT_MGR')
    const w = mount(AppShell, { global: { plugins: [router] } })
    expect(w.text()).toContain('工作台')
    expect(w.text()).toContain('博威无纸化')
    expect(w.find('.scope-indicator').text()).toContain('全部')
  })
  it('collapse toggle changes width class', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    await w.find('.collapse-btn').trigger('click')
    expect(w.find('.shell').classes()).toContain('collapsed')
  })
})