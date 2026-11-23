import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))
vi.mock('../api/ledger', () => ({
  rolesMatrix: vi.fn(() => Promise.resolve([])),
  listProjects: vi.fn(() => Promise.resolve([])),
  listUseUnits: vi.fn(() => Promise.resolve([])),
  listOrg: vi.fn(() => Promise.resolve([])),
  listContractsLedger: vi.fn(() => Promise.resolve([])),
  createProject: vi.fn(),
  updateProject: vi.fn()
}))
vi.mock('../api/handover', () => ({ listHandovers: vi.fn(() => Promise.resolve([])) }))

import SettingsView from '../views/SettingsView.vue'
import RolesView from '../views/RolesView.vue'
import ProjectsView from '../views/ProjectsView.vue'
import PageMeta from './PageMeta.vue'
import { useAuthStore } from '../stores/auth'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/settings', component: { template: '<div/>' } },
  { path: '/roles', component: { template: '<div/>' } },
  { path: '/projects', component: { template: '<div/>' } }
] })

function useMultiRole() {
  const auth = useAuthStore()
  auth.role = 'WORKER'
  auth.roles = ['WORKER', 'PLATFORM_ADMIN']
}

function mountWithRouter(component, options = {}) {
  return mount(component, { ...options, global: { plugins: [router] } })
}

describe('多角色 UI 权限并集', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    useMultiRole()
    await router.push('/')
    await router.isReady()
  })

  it('SettingsView 使用次要角色的配置能力', async () => {
    const w = mountWithRouter(SettingsView)
    await flushPromises()

    expect(w.find('.tab-bar').text()).toContain('系统配置')
  })

  it('RolesView 使用次要角色的授权管理入口', async () => {
    const w = mountWithRouter(RolesView)
    await flushPromises()

    expect(w.find('.tab-bar').text()).toContain('授权管理')
  })

  it('ProjectsView 使用次要角色的合同台账入口', async () => {
    const w = mountWithRouter(ProjectsView)
    await flushPromises()

    expect(w.find('.tab-bar').text()).toContain('合同管理')
  })

  it('PageMeta 展示角色集并集权限', async () => {
    const w = mountWithRouter(PageMeta, { props: { perm: 'config:manage' } })
    await flushPromises()

    expect(w.text()).toContain('平台管理员')
    expect(w.text()).toContain('config:manage = Y')
  })

  it('单角色 PageMeta 权限行为不变', async () => {
    const auth = useAuthStore()
    auth.roles = ['WORKER']
    const w = mountWithRouter(PageMeta, { props: { perm: 'config:manage' } })
    await flushPromises()

    expect(w.text()).toContain('config:manage = DENY')
    expect(w.text()).not.toContain('平台管理员')
  })
})
