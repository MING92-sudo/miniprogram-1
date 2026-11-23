const { getOrderList, resolveByElevatorCode } = require('../../../services/order')
const { STATUS_TEXT, REPORT_STATUS_TEXT, TOAST_TEXT, EMPTY_TEXT, POOL_TEXT } = require('../../../constants/index')
const { formatTime, parseTime } = require('../../../utils/util')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const TABS = [
  { key: '', label: '全部' },
  { key: 'OPEN', label: '未完成' },
  { key: 'PENDING', label: STATUS_TEXT.PENDING },
  { key: 'PROCESSING', label: STATUS_TEXT.PROCESSING },
  { key: 'DONE', label: STATUS_TEXT.DONE }
]
const DUE_LABEL = { today: '今日到期', soon: '即将到期（3 天内）', overdue: '保养超期' }

// 卡片展示投影（纯函数）：到期标记/已作业时长/上报状态/人员与设备行，不新增接口字段
function enrich(o) {
  const item = Object.assign({}, o, { statusText: STATUS_TEXT[o.status] || o.status })
  const today = formatTime().slice(0, 10)
  const soonEnd = formatTime(new Date(Date.now() + 3 * 86400000)).slice(0, 10)
  const day = (o.planTime || '').slice(0, 10)
  if (o.status !== 'DONE' && day) {
    item.dueTag = day < today ? { text: '已超期', cls: 'sig' }
      : (day === today ? { text: '今日到期', cls: 'amber' }
        : (day <= soonEnd ? { text: '3 日内到期', cls: '' } : null))
  }
  const t0 = parseTime(o.checkinTime)
  if (o.status === 'PROCESSING' && t0) {
    const mins = Math.max(0, Math.floor((Date.now() - t0) / 60000))
    item.elapsedText = '已作业 ' + (mins >= 60 ? Math.floor(mins / 60) + ' 小时 ' + (mins % 60) + ' 分' : mins + ' 分钟')
  }
  if (o.status === 'DONE') {
    item.reportTag = o.reportStatus === 'REPORTED' ? { text: REPORT_STATUS_TEXT.REPORTED, cls: 'ok' }
      : (o.reportStatus === 'FAILED' ? { text: REPORT_STATUS_TEXT.FAILED, cls: 'sig' } : { text: REPORT_STATUS_TEXT.PENDING, cls: '' })
  }
  item.workerLine = (o.workerName || '') + (o.assistantName ? ' · 配合 ' + o.assistantName : '')
  item.metaLine = [o.projectName, o.insideNumber ? '编号 ' + o.insideNumber : '', o.model].filter(Boolean).join(' · ')
  item.deviceLine = [o.deviceCode ? '设备代码 ' + o.deviceCode : '', o.regCode ? '注册代码 ' + o.regCode : ''].filter(Boolean).join(' · ')
  return item
}

function isPoolOrder(o) {
  return o.status === 'PENDING' && !o.workerName && !o.workerPlatformId
}

definePage(ROUTES['/pkg-maintenance/pages/orders/index'], {
  data: {
    tabs: TABS, activeTab: '', list: [], poolList: [], mineList: [], page: 1, size: 20, total: 0,
    keyword: '', status: '', due: '', dueLabel: '', loading: true, error: '',
    poolText: POOL_TEXT,
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
    this._requestSeq = (this._requestSeq || 0) + 1
    const seq = this._requestSeq
    const query = {
      page: reset ? 1 : this.data.page + 1,
      size: this.data.size,
      keyword: this.data.keyword,
      status: this.data.status,
      due: this._due || ''
    }
    this.setData({ loading: reset, error: '' })
    return getOrderList(query)
      .then((data) => {
        if (seq !== this._requestSeq) return
        const list = ((data && data.list) || []).map(enrich)
        const merged = reset ? list : this.data.list.concat(list)
        this.setData({
          list: merged,
          poolList: merged.filter(isPoolOrder),
          mineList: merged.filter((o) => !isPoolOrder(o)),
          total: (data && data.total) || 0, page: query.page, loading: false,
          emptyMode: query.keyword || query.status || query.due ? 'filtered' : 'empty'
        })
      })
      .catch((e) => {
        if (seq !== this._requestSeq) return
        this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED })
      })
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