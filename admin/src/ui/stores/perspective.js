import { defineStore } from 'pinia'
import { defaultScope, canonical } from '../permission-v2'
import { useAuthStore } from '../../stores/auth'

export const usePerspectiveStore = defineStore('perspective', {
  getters: {
    /** 真实登录角色（后端 auth 下发的 role，经 ALIAS 归一为权限矩阵角色码） */
    activeRole() {
      const auth = useAuthStore()
      return canonical(auth.role) || auth.role || ''
    },
    /** 当前账号持有角色集：真实系统一人一角色，恒为单选 */
    roleSet() {
      const r = this.activeRole
      return r ? [r] : []
    },
    scope() {
      return defaultScope(this.activeRole) || 'SELF'
    }
  },
  actions: {
    // 兼容旧调用：角色不可在前端切换，这两个方法刻意留空。
    setRoleSet() {},
    setRole() {}
  }
})
