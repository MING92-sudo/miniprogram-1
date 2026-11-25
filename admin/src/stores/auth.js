import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { onUnauthorized } from '../api/client'
import { stateForRoles } from '../ui/permission-v2'
import router from '../router'

export const ADMIN_READ_ROLES = ['LEADER', 'ADMIN']
export const ADMIN_WRITE_ROLES = ['ADMIN']

function persist(token, user, role, roles) {
  if (token) {
    localStorage.setItem('admin_token', token)
    localStorage.setItem('admin_user', JSON.stringify(user))
    localStorage.setItem('admin_role', role)
    localStorage.setItem('admin_roles', JSON.stringify(roles || []))
  } else {
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_user')
    localStorage.removeItem('admin_role')
    localStorage.removeItem('admin_roles')
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('admin_token') || '',
    user: JSON.parse(localStorage.getItem('admin_user') || 'null'),
    role: localStorage.getItem('admin_role') || '',
    roles: JSON.parse(localStorage.getItem('admin_roles') || '[]')
  }),
  getters: {
    isLogin: (s) => Boolean(s.token),
    canWrite: (s) => ADMIN_WRITE_ROLES.includes(s.role),
    isSysAdmin: (s) => s.role === 'ADMIN' || s.role === 'PLATFORM_ADMIN',
    canRead: (s) => ADMIN_READ_ROLES.includes(s.role),
    can: (s) => (capability) => {
      try {
        const st = stateForRoles(Array.isArray(s.roles) && s.roles.length ? s.roles : [s.role], capability)
        return st === 'Y' || st === 'C'
      } catch {
        return false
      }
    },
    userName: (s) => (s.user && s.user.name) || s.role || ''
  },
  actions: {
    async login(phone, password) {
      // client=admin：后端校验管理端角色白名单，WORKER/UNIT_ADMIN 登录被拒（403）
      const data = await authApi.login(phone, password)
      this.token = data.token
      this.user = data.userInfo
      this.role = data.role
      this.roles = Array.isArray(data.roles) ? data.roles : []
      persist(data.token, data.userInfo, data.role, this.roles)
      return data
    },
    async logout() {
      // 先吊销服务端会话：此时 token 仍在 localStorage，请求正常携带 Authorization
      await authApi.logout().catch(() => {})
      this.token = ''
      this.user = null
      this.role = ''
      this.roles = []
      persist('', null, '', [])
      router.push('/login')
    },
    clearSession() {
      this.token = ''
      this.user = null
      this.role = ''
      this.roles = []
      persist('', null, '', [])
    }
  }
})

onUnauthorized(() => {
  const auth = useAuthStore()
  auth.clearSession()
  router.push('/login')
})
