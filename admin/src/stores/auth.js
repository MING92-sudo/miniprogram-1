import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { onUnauthorized } from '../api/client'
import router from '../router'

/** 管理端角色口径（与后端 auth/AdminRoles.java 对齐） */
export const ADMIN_READ_ROLES = ['LEADER', 'ADMIN', 'SYS_ADMIN']
export const ADMIN_WRITE_ROLES = ['ADMIN', 'SYS_ADMIN']

function persist(token, user, role) {
  if (token) {
    localStorage.setItem('admin_token', token)
    localStorage.setItem('admin_user', JSON.stringify(user))
    localStorage.setItem('admin_role', role)
  } else {
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_user')
    localStorage.removeItem('admin_role')
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('admin_token') || '',
    user: JSON.parse(localStorage.getItem('admin_user') || 'null'),
    role: localStorage.getItem('admin_role') || ''
  }),
  getters: {
    isLogin: (s) => Boolean(s.token),
    canWrite: (s) => ADMIN_WRITE_ROLES.includes(s.role),
    isSysAdmin: (s) => s.role === 'SYS_ADMIN',
    canRead: (s) => ADMIN_READ_ROLES.includes(s.role),
    userName: (s) => (s.user && s.user.name) || s.role || ''
  },
  actions: {
    async login(phone, password) {
      // client=admin：后端校验管理端角色白名单，WORKER/UNIT_ADMIN 登录被拒（403）
      const data = await authApi.login(phone, password)
      this.token = data.token
      this.user = data.userInfo
      this.role = data.role
      persist(data.token, data.userInfo, data.role)
      return data
    },
    logout() {
      this.token = ''
      this.user = null
      this.role = ''
      persist('', null, '')
      authApi.logout().catch(() => {})
      router.push('/login')
    },
    clearSession() {
      this.token = ''
      this.user = null
      this.role = ''
      persist('', null, '')
    }
  }
})

// 401（登录过期/被顶下线）统一清会话跳登录
onUnauthorized(() => {
  const auth = useAuthStore()
  auth.clearSession()
  router.push('/login')
})
