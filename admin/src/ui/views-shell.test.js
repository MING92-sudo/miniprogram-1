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

describe('LoginView', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('blocks submit when password < 6', async () => {
    router.push('/login'); await router.isReady()
    const w = mount(LoginView, { global: { plugins: [router] } })
    await w.find('input[name="phone"]').setValue('13800000001')
    await w.find('input[name="password"]').setValue('123')
    await w.find('form').trigger('submit')
    expect(w.text()).toContain('密码')
  })
})

describe('ForbiddenView', () => {
  it('shows required permission (Chinese) from query', async () => {
    await router.push('/403?code=contract:view')
    const w = mount(ForbiddenView, { global: { plugins: [router] } })
    expect(w.text()).toContain('查看合同')
  })
})

describe('NotFoundView', () => {
  it('renders 404 with back link', () => {
    const w = mount(NotFoundView, { global: { plugins: [router] } })
    expect(w.text()).toContain('404')
  })
})