// 报修接单池（M3，屏 07）：七态筛选 + 急修优先排序 + 抢单（order:grab 本区域）+ 四态双分支。
// 「一键报修」入口按权限面渲染（2026-11-20 真机反馈）：仅 order:create/order:create_proxy 可见
// （routes 报修页 perm 同口径；MAINT_TECH 无报修权限不显示，权限面=渲染面 docs/14 §7.1）。
const { getFaultList, grabFault } = require('../../../services/fault')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
const { getPerms } = require('../../../utils/auth')
const { TOAST_TEXT, FAULT_STATUS_TEXT, FAULT_STATUS_SHORT, FAULT_STATUS_TONE } = require('../../../constants/index')

const STATUS_TEXT = FAULT_STATUS_TEXT
const STATUS_TONE = FAULT_STATUS_TONE
const TABS = [
  { key: '', label: '全部' }, { key: 'OPEN', label: FAULT_STATUS_SHORT.OPEN },
  { key: 'PROCESSING', label: FAULT_STATUS_SHORT.PROCESSING },
  { key: 'PENDING_CONFIRM', label: FAULT_STATUS_SHORT.PENDING_CONFIRM }, { key: 'CLOSED', label: FAULT_STATUS_SHORT.CLOSED }
]

definePage(ROUTES['/pages/order/list/index'], {
  data: { tabs: TABS, activeTab: '', list: [], page: 1, size: 20, total: 0, status: '', trapped: '', loading: true, error: '', emptyMode: 'empty', canReport: false },
  onLoad(query) {
    const perms = getPerms() || []
    this.setData({ canReport: perms.indexOf('order:create') > -1 || perms.indexOf('order:create_proxy') > -1 })
    // C-18/V62：「急修待接」KPI 下钻只看困人单（trapped=1）；「报修待接」为其余待接单
    if (query && query.state === 'urgent') this.setData({ status: 'OPEN', activeTab: 'OPEN', trapped: '1' })
    else if (query && query.state === 'pending') this.setData({ status: 'OPEN', activeTab: 'OPEN', trapped: '0' })
  },
  onShow() { this.fetchList(true) },
  onPullDownRefresh() { this.fetchList(true).then(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.fetchList(false) },
  onTabChange(e) { const key = e.currentTarget.dataset.key; this.setData({ status: key, activeTab: key, trapped: '' }); this.fetchList(true) },
  fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: reset, error: '' })
    return getFaultList({ page, size: this.data.size, status: this.data.status, trapped: this.data.trapped }).then((data) => {
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