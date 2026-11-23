import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(() => Promise.resolve())
}))
vi.mock('../api/client', () => ({ onUnauthorized: vi.fn() }))
vi.mock('../router', () => ({ default: { push: vi.fn() } }))

import * as authApi from '../api/auth'
import { useAuthStore } from './auth'
import { usePerspectiveStore } from '../ui/stores/perspective'

describe('auth store 多角色并集', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loginPersistsRoleUnion：登录保留 roles 并写入 localStorage，perspective 同时可见', async () => {
    authApi.login.mockResolvedValue({
      token: 'jwt',
      userInfo: { name: '张三' },
      role: 'INST_MGR',
      roles: ['INST_MGR', 'MAINT_MGR']
    })
    const auth = useAuthStore()
    const perspective = usePerspectiveStore()

    await auth.login('13800000000', 'pw')

    expect(auth.role).toBe('INST_MGR')
    expect(auth.roles).toEqual(['INST_MGR', 'MAINT_MGR'])
    expect(perspective.roleSet).toEqual(['INST_MGR', 'MAINT_MGR'])
    expect(JSON.parse(localStorage.getItem('admin_roles'))).toEqual(['INST_MGR', 'MAINT_MGR'])
    expect(localStorage.getItem('admin_role')).toBe('INST_MGR')
  })

  it('clearSession 同时清除 admin_roles', async () => {
    authApi.login.mockResolvedValue({
      token: 'jwt', userInfo: { name: '张三' }, role: 'INST_MGR', roles: ['INST_MGR', 'MAINT_MGR']
    })
    const auth = useAuthStore()
    await auth.login('13800000000', 'pw')

    auth.clearSession()

    expect(auth.roles).toEqual([])
    expect(localStorage.getItem('admin_roles')).toBeNull()
  })
})
