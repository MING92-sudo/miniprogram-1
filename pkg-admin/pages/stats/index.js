// 统计（M6 只读投影，指标口径随角色数据范围，公式见管理端）
const admin = require('../../../services/admin')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
definePage(ROUTES['/pkg-admin/pages/stats/index'], {
  data: { stats: null, loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() { this.setData({ loading: true, error: '' }); admin.getStats().then((s) => this.setData({ stats: s, loading: false })).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' })) },
  onRetry() { this.load() }
})
