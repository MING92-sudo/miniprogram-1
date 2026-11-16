// 待办签字（M5）：合并待签记录单 + 待确认急修单；P1 T8-B5：client:sign 为 CLIENT_ADMIN/CLIENT_SIGNER
// 双 Y（docs/14 §5.4 两角色能力相同），两角色均可签，不再仅 CLIENT_SIGNER。
const unit = require('../../../services/unit')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { hasAnyRole } = require('../../../utils/auth')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-client/pages/todo/index'], {
  data: { records: [], faults: [], loading: true, error: '', canSign: false },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '', canSign: hasAnyRole(['CLIENT_ADMIN', 'CLIENT_SIGNER']) })
    Promise.all([
      unit.getUnitPendingSigns().catch(() => []),
      unit.getUnitFaults().catch(() => [])
    ]).then(([records, faults]) => {
      this.setData({
        records: records || [],
        faults: (faults || []).filter((f) => f.status === 'PENDING_CONFIRM'),
        loading: false
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  goSign(e) {
    const r = e.currentTarget.dataset.item
    nav.open('/pkg-client/pages/sign/index?taskId=' + r.id + '&token=' + (r.shareToken || ''))
  },
  goFault(e) { nav.open('/pages/order/detail/index?id=' + e.currentTarget.dataset.id) }
})