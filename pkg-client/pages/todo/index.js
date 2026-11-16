// 待办签字（M5）：待签记录单 + 待确认急修单合并视图。P1 T8-B5：client:sign 为 CLIENT_ADMIN/
// CLIENT_SIGNER 双 Y（docs/14 §5.4 两角色能力相同），签字权由路由 perm 保证，页内不再算 canSign。
// P1 T8-I2（fix round 1，spec D1 精神）：客户急修确认前后端双重关闭（order:confirm 收回 T2、
// POST /faults/{id}/confirm 不在客户白名单 T3），客户主体隐藏「待确认急修单」区块并停发
// getUnitFaults（首页 KPI 聚合用途不受影响）；非客户主体保持原合并视图。
const unit = require('../../../services/unit')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { isCustomerPrincipal } = require('../../../utils/auth')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-client/pages/todo/index'], {
  data: { records: [], faults: [], loading: true, error: '', showFaults: false },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    const customer = isCustomerPrincipal()
    this.setData({ loading: true, error: '', showFaults: !customer })
    Promise.all([
      unit.getUnitPendingSigns().catch(() => []),
      customer ? Promise.resolve([]) : unit.getUnitFaults().catch(() => [])
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