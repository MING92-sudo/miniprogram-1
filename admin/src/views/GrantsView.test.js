import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/ledger', () => ({
  listInvites: vi.fn(),
  createInvite: vi.fn(),
  withdrawInvite: vi.fn(),
  listGrants: vi.fn(),
  createGrant: vi.fn(),
  withdrawGrant: vi.fn(),
  listOrg: vi.fn(),
  listUsers: vi.fn()
}))

import { listInvites, createInvite, listGrants, listOrg, listUsers } from '../api/ledger'
import { useAuthStore } from '../stores/auth'
import GrantsView from './GrantsView.vue'

const inviteRows = [{
  id: 'invite-1',
  code: 'INV-20261122',
  type: 'INVITE',
  targetOrgId: 'org-maint',
  createdBy: 'user-admin',
  validDays: 7,
  joinedCount: 2,
  status: 'ACTIVE',
  createdAt: '2026-11-22 10:00:00'
}]
const grantRows = [{
  id: 'grant-1',
  granteeId: 'user-tech',
  permCode: 'record:review',
  scope: 'PROJECT',
  startAt: '2026-11-22 10:00:00',
  endAt: '2026-11-23 10:00:00',
  status: 'ACTIVE'
}]

async function mountView() {
  const wrapper = mount(GrantsView, { global: { stubs: { PageMeta: true } } })
  await flushPromises()
  return wrapper
}

describe('GrantsView 邀请与授权字段映射', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'ADMIN'
    vi.clearAllMocks()
    listInvites.mockResolvedValue(inviteRows)
    listGrants.mockResolvedValue(grantRows)
    listOrg.mockResolvedValue([{
      id: 'org-root',
      name: '重庆博威电梯有限公司',
      children: [{ id: 'org-maint', name: '维保一部', children: [] }]
    }])
    listUsers.mockResolvedValue([
      { id: 'user-admin', name: '王管理员', roles: ['PLATFORM_ADMIN'] },
      { id: 'user-tech', name: '刘技师', roles: ['MAINT_TECH'] }
    ])
    createInvite.mockResolvedValue({})
  })

  it('按组织和人员数据渲染目标组织、创建人、被授权人及原角色', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('维保一部')
    expect(wrapper.text()).toContain('王管理员')
    expect(wrapper.text()).toContain('刘技师')
    expect(wrapper.text()).toContain('维保人员')
  })

  it('生成邀请码时按后端契约提交 targetOrgId', async () => {
    const wrapper = await mountView()
    const openButton = wrapper.findAll('button').find(button => button.text().includes('生成邀请码'))
    await openButton.trigger('click')

    const dialog = wrapper.find('[role="dialog"]')
    await dialog.findAll('select')[1].setValue('org-root')
    await dialog.find('.modal-foot .btn-primary').trigger('click')
    await flushPromises()

    expect(createInvite).toHaveBeenCalledWith({
      type: 'INVITE',
      targetOrgId: 'org-root',
      validDays: 7
    })
  })
})
