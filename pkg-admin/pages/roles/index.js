// 角色（M6 只读投影，后端 MultiRoleGate 强校验；写操作在管理端）
const admin = require('../../../services/admin')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
definePage(ROUTES['/pkg-admin/pages/roles/index'], {
  data: { list: [], loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() { this.setData({ loading: true, error: '' }); admin.getRolesMatrix({ page: 1, size: 50 }).then((res) => this.setData({ list: (res && res.list) || (Array.isArray(res) ? res : []), loading: false })).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' })) },
  onRetry() { this.load() }
})
