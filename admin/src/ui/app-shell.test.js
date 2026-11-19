import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import AppShell from './AppShell.vue'
import { useAuthStore } from '../stores/auth'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div>workbench</div>' }, meta: { title: '工作台', group: '概览' } }
] })

describe('AppShell（真实登录角色驱动）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.innerWidth = 1440
    useAuthStore().role = 'MAINT_MGR'
  })
  it('renders sidebar groups, brand and scope pill', async () => {
    router.push('/dashboard'); await router.isReady()
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
  it('shows the server-issued role as read-only (no switcher menu)', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    expect(w.find('.role-switch > button').exists()).toBe(false)
    expect(w.find('.role-switch .rs-static').text().length).toBeGreaterThan(0)
    expect(w.find('.role-menu').exists()).toBe(false)
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