import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'
import NotFoundView from '../views/NotFoundView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/login', component: LoginView }, { path: '/403', component: ForbiddenView },
  { path: '/dashboard', component: { template: '<div/>' } }
] })

describe('LoginView（原型 split 布局 + 真实鉴权）', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('blocks submit when password < 6', async () => {
    router.push('/login'); await router.isReady()
    const w = mount(LoginView, { global: { plugins: [router] } })
    await w.find('input[name="phone"]').setValue('13800000001')
    await w.find('input[name="password"]').setValue('123')
    await w.find('form').trigger('submit')
    expect(w.text()).toContain('密码')
  })
  it('renders brand panel, business chains and demo account quick-fill', async () => {
    router.push('/login'); await router.isReady()
    const w = mount(LoginView, { global: { plugins: [router] } })
    expect(w.text()).toContain('博威无纸化')
    expect(w.find('.login-h1').exists()).toBe(true)
    expect(w.findAll('.chain').length).toBe(3)
    const demos = w.findAll('.demo-acct')
    expect(demos.length).toBe(5)
    await demos[0].trigger('click')
    expect(w.find('input[name="phone"]').element.value).toBe('13800000009')
  })
})

describe('ForbiddenView', () => {
  it('shows required permission code from query', async () => {
    await router.push('/403?code=contract:view')
    const w = mount(ForbiddenView, { global: { plugins: [router] } })
    expect(w.text()).toContain('contract:view')
    expect(w.find('.code-big').text()).toBe('403')
  })
})

describe('NotFoundView', () => {
  it('renders 404 with back action', () => {
    const w = mount(NotFoundView, { global: { plugins: [router] } })
    expect(w.text()).toContain('404')
  })
})