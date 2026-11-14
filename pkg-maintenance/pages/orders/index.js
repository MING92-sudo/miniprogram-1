// 我的维保工单（M3 迁自 pages/order/list，逻辑保留）：状态筛选 + 搜索/扫码 + 到期下钻 + 分页。
const { getOrderList, resolveByElevatorCode } = require('../../../services/order')
const { STATUS_TEXT, TOAST_TEXT, EMPTY_TEXT } = require('../../../constants/index')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const TABS = [
  { key: '', label: '全部' },
  { key: 'PENDING', label: '待执行' },
  { key: 'PROCESSING', label: '进行中' },
  { key: 'DONE', label: '已完成' }
]
const DUE_LABEL = { today: '今日到期', soon: '即将到期（3 天内）', overdue: '保养超期' }

definePage(ROUTES['/pkg-maintenance/pages/orders/index'], {
  data: {
    tabs: TABS, activeTab: '', list: [], page: 1, size: 20, total: 0,
    keyword: '', status: '', due: '', dueLabel: '', loading: true, error: '',
    emptyMode: 'empty', emptyTitle: EMPTY_TEXT.ORDER || '暂无工单'
  },
  onLoad(query) {
    if (query && query.due && DUE_LABEL[query.due]) {
      this.setData({ due: query.due, dueLabel: DUE_LABEL[query.due] })
      this._due = query.due
    }
    if (query && query.status) {
      this.setData({ status: query.status, activeTab: query.status })
    }
  },
  onShow() {
    this.applyDueFromStorage()
    this.applyStatusFromStorage()
    this.fetchList(true)
  },
  applyDueFromStorage() {
    const due = wx.getStorageSync('order_due_filter')
    if (due && DUE_LABEL[due]) {
      this._due = due
      this.setData({ due, dueLabel: DUE_LABEL[due], status: '', activeTab: '' })
    }
    wx.removeStorageSync('order_due_filter')
  },
  applyStatusFromStorage() {
    const st = wx.getStorageSync('order_status_filter')
    if (st && st !== this.data.status) {
      this._due = ''
      this.setData({ status: st, activeTab: st, due: '', dueLabel: '' })
    }
    wx.removeStorageSync('order_status_filter')
  },
  onPullDownRefresh() { this.fetchList(true).then(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.fetchList(false) },
  fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: reset, error: '' })
    return getOrderList({ page, size: this.data.size, keyword: this.data.keyword, status: this.data.status, due: this._due || '' })
      .then((data) => {
        const list = ((data && data.list) || []).map((o) => Object.assign({}, o, { statusText: STATUS_TEXT[o.status] || o.status }))
        const merged = reset ? list : this.data.list.concat(list)
        this.setData({ list: merged, total: (data && data.total) || 0, page, loading: false, emptyMode: this.data.keyword || this.data.status || this._due ? 'filtered' : 'empty' })
      })
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchList(true) },
  onTabChange(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ status: key, activeTab: key })
    this.fetchList(true)
  },
  clearDue() { this._due = ''; this.setData({ due: '', dueLabel: '' }); this.fetchList(true) },
  onSearch(e) { this.setData({ keyword: e.detail.value }); this.fetchList(true) },
  onScan() {
    wx.scanCode({
      onlyFromCamera: true,
      success: (res) => {
        const code = String(res.result || '').trim()
        if (!code) return
        resolveByElevatorCode(code)
          .then((order) => nav.open('/pkg-maintenance/pages/task/index?orderId=' + order.id))
          .catch((e) => wx.showModal({ title: '扫码结果', content: e.message || '未找到关联工单', showCancel: false }))
      }
    })
  },
  goDetail(e) { nav.open('/pkg-maintenance/pages/task/index?orderId=' + e.currentTarget.dataset.id) },
  onAction(e) {
    const { id, status } = e.currentTarget.dataset
    if (status === 'PENDING') nav.open('/pkg-maintenance/pages/checkin/index?orderId=' + id)
    else if (status === 'PROCESSING') nav.open('/pkg-maintenance/pages/task/index?orderId=' + id)
    else nav.open('/pkg-maintenance/pages/task/index?orderId=' + id)
  },
  onBack() { nav.back() }
})