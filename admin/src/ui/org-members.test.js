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

import http from '../api/client'
import OrgView from '../views/OrgView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/org', component: { template: '<div/>' } }
] })

const TREE = [
  { id: 'org_co', name: '云维电梯公司', type: 'COMPANY', status: 'NORMAL' },
  { id: 'org_dept', name: '维保部', type: 'DEPT', parentId: 'org_co', status: 'NORMAL' },
  { id: 'org_team_1', name: '维保一组', type: 'TEAM', parentId: 'org_dept', status: 'NORMAL' },
  { id: 'org_team_2', name: '维保二组', type: 'TEAM', parentId: 'org_dept', status: 'NORMAL' }
]
const MEMBERS = [
  { id: 'emp_1', name: '张三', phone: '13800000001', role: 'MAINT_TECH', roleText: '维保人员', groupName: '维保一组', regionId: null, regionName: null, enabled: true }
]

async function mountOrg() {
  const w = mount(OrgView, { global: { plugins: [router] } })
  await flushPromises()
  return w
}
async function openTeamDrawer(w) {
  await w.find('[data-org="org_team_1"]').trigger('click')
  await flushPromises()
}

describe('OrgView 成员区块与调人（D1）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    router.push('/')
    http.get.mockImplementation((url) => {
      if (url === '/org') return Promise.resolve(TREE)
      if (url === '/org/org_team_1/members') return Promise.resolve(MEMBERS)
      return Promise.resolve([])
    })
    http.put.mockReset()
    http.put.mockResolvedValue({})
  })

  it('shows members in node drawer', async () => {
    const w = await mountOrg()
    await openTeamDrawer(w)
    expect(http.get).toHaveBeenCalledWith('/org/org_team_1/members')
    expect(w.text()).toContain('张三')
  })

  it('move member calls update with target team', async () => {
    const w = await mountOrg()
    await openTeamDrawer(w)
    const sel = w.find('.member-row select')
    expect(sel.exists()).toBe(true)
    await sel.setValue('维保二组')
    await w.find('.member-row button').trigger('click')
    await flushPromises()
    // 二次确认弹层
    expect(w.text()).toContain('确认把 张三 调到 维保二组？')
    const ok = w.findAll('button').find(b => b.text() === '确认调人')
    expect(ok).toBeTruthy()
    await ok.trigger('click')
    await flushPromises()
    expect(http.put).toHaveBeenCalledWith('/employees/emp_1', { groupName: '维保二组' })
  })
})
