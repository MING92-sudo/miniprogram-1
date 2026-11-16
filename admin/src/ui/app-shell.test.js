import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import AppShell from './AppShell.vue'
import { usePerspectiveStore } from './stores/perspective'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div>workbench</div>' }, meta: { title: '工作台', group: '概览' } }
] })

describe('AppShell（原型 shellHtml 对齐）', () => {
  beforeEach(() => { setActivePinia(createPinia()); window.innerWidth = 1440 })
  it('renders sidebar groups, brand and scope pill', async () => {
    router.push('/dashboard'); await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['MAINT_MGR']); p.setRole('MAINT_MGR')
    const w = mount(AppShell, { global: { plugins: [router] } })
    expect(w.text()).toContain('工作台')
    expect(w.text()).toContain('博威无纸化')
    expect(w.text()).toContain('ADMIN CONSOLE')
    expect(w.find('.scope-pill').text()).toContain('全部')
  })
  it('collapse toggle changes grid class', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    await w.find('button[aria-label="折叠侧边栏"]').trigger('click')
    expect(w.find('.shell').classes()).toContain('collapsed')
  })
  it('role switcher menu lists demo personas and switches perspective', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    await w.find('.role-switch > button').trigger('click')
    expect(w.find('.role-menu').classes()).toContain('open')
    const opts = w.findAll('.role-opt')
    expect(opts.length).toBe(5)
    await opts[1].trigger('click') // MAINT_MGR
    expect(usePerspectiveStore().activeRole).toBe('MAINT_MGR')
  })
  it('sim toggle shows error state with retry', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    await w.find('.sim').trigger('click')
    expect(w.find('.err-state').exists()).toBe(true)
    await w.find('.err-state .btn-primary').trigger('click') // 重试
    expect(w.find('.err-state').exists()).toBe(false)
  })
})