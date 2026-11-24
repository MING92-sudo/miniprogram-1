const admin = require('../../services/admin')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

// /roles-matrix 返回权限码 × 角色矩阵：[{ code, states: { ROLE: 'Y'|'C'|'-' } }]
definePage(ROUTES['/pkg-admin/pages/roles/index'], {
  data: { list: [], loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    admin.getRolesMatrix({ page: 1, size: 50 }).then((res) => {
      const rows = (res && res.list) || (Array.isArray(res) ? res : [])
      const list = rows.map((r) => {
        const states = r.states || {}
        const allowed = Object.keys(states).filter((role) => states[role] === 'Y')
        const conditional = Object.keys(states).filter((role) => states[role] === 'C')
        return { code: r.code, allowed, conditional }
      })
      this.setData({ list, loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() }
})
