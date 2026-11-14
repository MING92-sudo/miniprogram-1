// 本单位工单（M5）：只读 + 进度（order:view_own，后端本单位谓词收敛）。
const unit = require('../../../services/unit')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const STATUS_TEXT = { OPEN: '待接单', ASSIGNED: '已接单', PROCESSING: '处置中', PENDING_CONFIRM: '待确认', CLOSED: '已闭环', REFUSED: '待整改', CANCELLED: '已取消' }
const STATUS_TONE = { OPEN: 'signal', ASSIGNED: 'amber', PROCESSING: 'amber', PENDING_CONFIRM: 'amber', CLOSED: 'ok', REFUSED: 'signal', CANCELLED: 'dim' }

definePage(ROUTES['/pkg-client/pages/orders/index'], {
  data: { list: [], loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    unit.getUnitFaults().then((list) => {
      this.setData({ list: (list || []).map((f) => Object.assign({}, f, { statusText: STATUS_TEXT[f.status] || f.status, statusTone: STATUS_TONE[f.status] || 'dim' })), loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  goDetail(e) { nav.open('/pages/order/detail/index?id=' + e.currentTarget.dataset.id) },
  goReport() { nav.open('/pages/order/report/index') }
})