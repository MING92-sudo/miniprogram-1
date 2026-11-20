import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'

// Task 5（D5）：账号管理维护所属区域——开通/编辑表单「所属区域」下拉（可空=不限区域）、
// 列表「区域」列、详情区域行。区域选项来自 GET /regions；开通统一走 POST /employees 带 regionId。
vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({}))
  },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import { useAuthStore } from '../stores/auth'
import UsersView from '../views/UsersView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/users', component: { template: '<div/>' } }
] })

const USERS = [
  { id: 'emp_1', name: '张三', phone: '13800000001', department: 'MAINT', status: 'NORMAL', roles: ['MAINT_TECH'] }
]
// GET /employees 行投影（Task 1 产出）：regionId/regionName 合并进账号列表行
const EMPLOYEES = [
  { id: 'emp_1', name: '张三', phone: '13800000001', role: 'MAINT_TECH', regionId: 'reg_1', regionName: '渝北区', enabled: true }
]
const REGIONS = [
  { id: 'reg_1', name: '渝北区' },
  { id: 'reg_2', name: '江北区' }
]

async function mountUsers() {
  const w = mount(UsersView, { global: { plugins: [router] } })
  await flushPromises()
  return w
}
async function openCreateModal(w) {
  const btn = w.findAll('button').find(b => b.text().includes('新增用户'))
  expect(btn, '工具栏应有「新增用户」按钮').toBeTruthy()
  await btn.trigger('click')
  await flushPromises()
}
function createModal(w) {
  return w.findAll('.modal').find(m => m.text().includes('开通账号'))
}
function regionField(scope) {
  return scope.findAll('.field').find(f => f.find('label').exists() && f.find('label').text().includes('所属区域'))
}

describe('UsersView 所属区域维护（D5）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'ADMIN' // user:create=Y，PermButton 渲染
    router.push('/')
    http.get.mockImplementation((url) => {
      if (url === '/users') return Promise.resolve(USERS)
      if (url === '/employees') return Promise.resolve(EMPLOYEES)
      if (url === '/regions') return Promise.resolve(REGIONS)
      return Promise.resolve([])
    })
    http.post.mockReset()
    http.post.mockResolvedValue({ id: 'emp_new', initialPassword: 'pw12345678' })
    http.put.mockReset()
    http.put.mockResolvedValue({})
  })

  it('create form includes region select', async () => {
    const w = await mountUsers()
    await openCreateModal(w)
    const field = regionField(createModal(w))
    expect(field, '开通表单应有「所属区域」字段').toBeTruthy()
    const sel = field.find('select')
    expect(sel.exists(), '所属区域应为下拉选择').toBe(true)
    const opts = sel.findAll('option')
    expect(opts.map(o => o.element.value)).toContain('reg_1')
    expect(opts.map(o => o.text())).toContain('渝北区')
    // 可空：存在空值选项=不限区域
    expect(opts.some(o => o.element.value === '')).toBe(true)
    expect(field.text()).toContain('不限区域')
  })

  it('submit carries regionId', async () => {
    const w = await mountUsers()
    await openCreateModal(w)
    const modal = createModal(w)
    await modal.find('input[placeholder="真实姓名"]').setValue('李四')
    await modal.find('input[placeholder="11 位手机号"]').setValue('13912345678')
    await modal.find('.perm-item input[type="checkbox"]').setValue(true)
    await regionField(modal).find('select').setValue('reg_1')
    const ok = modal.findAll('button').find(b => b.text().includes('确认开通'))
    expect(ok, '开通弹窗应有「确认开通」按钮').toBeTruthy()
    await ok.trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/employees', expect.objectContaining({
      name: '李四',
      phone: '13912345678',
      regionId: 'reg_1'
    }))
  })

  it('row shows regionName', async () => {
    const w = await mountUsers()
    expect(w.text()).toContain('渝北区')
  })

  // Fix 1：区域富化（GET /employees）失败不得拖垮主列表——降级为无区域列（口径同 loadRegions）
  it('list still renders when region enrichment fails', async () => {
    http.get.mockImplementation((url) => {
      if (url === '/users') return Promise.resolve(USERS)
      if (url === '/employees') return Promise.reject(new Error('boom'))
      if (url === '/regions') return Promise.resolve(REGIONS)
      return Promise.resolve([])
    })
    const w = await mountUsers()
    expect(w.text()).toContain('张三')
    expect(w.text()).not.toContain('渝北区')
  })

  // Fix 2：跨部门兼任（R7）提交前本地拦截——不发起任何请求，避免「建档成功、补角色 422」半成品
  it('cross-department roles blocked locally without any request', async () => {
    const w = await mountUsers()
    await openCreateModal(w)
    const modal = createModal(w)
    await modal.find('input[placeholder="真实姓名"]').setValue('王五')
    await modal.find('input[placeholder="11 位手机号"]').setValue('13912345679')
    await modal.find('input[value="MAINT_TECH"]').setValue(true)
    await modal.find('input[value="INSTALLER"]').setValue(true)
    const ok = modal.findAll('button').find(b => b.text().includes('确认开通'))
    await ok.trigger('click')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(w.text()).toContain('跨部门')
  })
})
