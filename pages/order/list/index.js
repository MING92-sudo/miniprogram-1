// 报修接单池（M3，屏 07）：七态筛选 + 急修优先排序 + 抢单（order:grab 本区域）+ 四态双分支。
const { getFaultList, grabFault } = require('../../../services/fault')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
const { TOAST_TEXT } = require('../../../constants/index')

const STATUS_TEXT = {
  OPEN: '待接单', ASSIGNED: '已接单', PROCESSING: '处置中', PENDING_CONFIRM: '待确认',
  CLOSED: '已闭环', REFUSED: '待整改', CANCELLED: '已取消'
}
const STATUS_TONE = {
  OPEN: 'signal', ASSIGNED: 'amber', PROCESSING: 'amber', PENDING_CONFIRM: 'amber',
  CLOSED: 'ok', REFUSED: 'signal', CANCELLED: 'dim'
}
const TABS = [
  { key: '', label: '全部' }, { key: 'OPEN', label: '待接单' }, { key: 'PROCESSING', label: '处置中' },
  { key: 'PENDING_CONFIRM', label: '待确认' }, { key: 'CLOSED', label: '已闭环' }
]

definePage(ROUTES['/pages/order/list/index'], {
  data: { tabs: TABS, activeTab: '', list: [], page: 1, size: 20, total: 0, status: '', loading: true, error: '', emptyMode: 'empty' },
  onLoad(query) {
    if (query && query.state === 'urgent') this.setData({ status: 'OPEN', activeTab: 'OPEN' })
    else if (query && query.state === 'pending') this.setData({ status: 'OPEN', activeTab: 'OPEN' })
  },
  onShow() { this.fetchList(true) },
  onPullDownRefresh() { this.fetchList(true).then(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.fetchList(false) },
  onTabChange(e) { const key = e.currentTarget.dataset.key; this.setData({ status: key, activeTab: key }); this.fetchList(true) },
  fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: reset, error: '' })
    return getFaultList({ page, size: this.data.size, status: this.data.status }).then((data) => {
      const list = ((data && data.list) || []).map((f) => Object.assign({}, f, {
        statusText: STATUS_TEXT[f.status] || f.status, statusTone: STATUS_TONE[f.status] || 'dim'
      })).sort((a, b) => (b.trappedCount || 0) - (a.trappedCount || 0))
      const merged = reset ? list : this.data.list.concat(list)
      this.setData({ list: merged, total: (data && data.total) || 0, page, loading: false, emptyMode: this.data.status ? 'filtered' : 'empty' })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchList(true) },
  onGrab(e) {
    const id = e.currentTarget.dataset.id
    grabFault(id).then(() => { wx.showToast({ title: '已接单', icon: 'success' }); this.fetchList(true) })
      .catch((er) => wx.showToast({ title: (er && er.message) || '抢单失败', icon: 'none' }))
  },
  goDetail(e) { nav.open('/pages/order/detail/index?id=' + e.currentTarget.dataset.id) },
  goReport() { nav.open('/pages/order/report/index') },
  onBack() { nav.back() }
})