// M2 视角 store：activeRole + roleSet（M2 基准集，M3 接后端）；驱动菜单/范围/门控
import { defineStore } from 'pinia'
import { defaultScope, canonical } from '../permission-v2'
export const usePerspectiveStore = defineStore('perspective', {
  state: () => ({
    roleSet: ['PLATFORM_ADMIN', 'MAINT_MGR', 'MAINT_SUP', 'INST_MGR', 'CLIENT_ADMIN'],
    activeRole: 'PLATFORM_ADMIN'
  }),
  getters: {
    scope: (s) => defaultScope(s.activeRole) || 'SELF'
  },
  actions: {
    setRoleSet(roles) {
      this.roleSet = roles.map(canonical).filter(Boolean)
      if (!this.roleSet.includes(this.activeRole)) this.activeRole = this.roleSet[0] || ''
    },
    setRole(role) { const r = canonical(role); if (r && this.roleSet.includes(r)) this.activeRole = r }
  }
})