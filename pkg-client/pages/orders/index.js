const unit = require('../../../services/unit')
const { FAULT_STATUS_TEXT, FAULT_STATUS_SHORT, FAULT_STATUS_TONE, TOAST_TEXT } = require('../../../constants/index')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

// 列表短文案优先（SHORT 覆盖全文的四个高频态），其余回退完整七态文案
const STATUS_TEXT = Object.assign({}, FAULT_STATUS_TEXT, FAULT_STATUS_SHORT)
const STATUS_TONE = FAULT_STATUS_TONE

definePage(ROUTES['/pkg-client/pages/orders/index'], {
  data: { list: [], loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    unit.getUnitFaults().then((list) => {
      this.setData({ list: (list || []).map((f) => Object.assign({}, f, { statusText: STATUS_TEXT[f.status] || f.status, statusTone: STATUS_TONE[f.status] || 'dim' })), loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.load() },
  goDetail(e) { nav.open('/pages/order/detail/index?id=' + e.currentTarget.dataset.id) },
  goReport() { nav.open('/pages/order/report/index') }
})