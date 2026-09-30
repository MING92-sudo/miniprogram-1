// 工单列表：看板式卡片（蓝色页头 + 搜索/扫码 + 电梯信息卡，UI 参照无纸化维保截屏）
const { getOrderList, resolveByElevatorCode } = require('../../services/order')
const { STATUS_TEXT } = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

const TABS = [
  { key: '', label: '全部' },
  { key: 'PENDING', label: '待执行' },
  { key: 'PROCESSING', label: '进行中' },
  { key: 'DONE', label: '已完成' }
]

// 首页统计卡下钻的到期维度文案（today/soon/overdue）
const DUE_LABEL = {
  today: '今日到期',
  soon: '即将到期（3 天内）',
  overdue: '保养超期'
}

Page({
  data: {
    tabs: TABS,
    activeTab: '',
    list: [],
    page: 1,
    size: 20,
    total: 0,
  keyword: '',
  status: '',
  due: '',
  dueLabel: '',
  loading: false
  },

  onLoad(query) {
    // 首页统计卡下钻：按到期维度过滤（today/soon/overdue）
    if (query && query.due && DUE_LABEL[query.due]) {
      this.setData({ due: query.due, dueLabel: DUE_LABEL[query.due] })
      this._due = query.due
    }
  },

  onShow() {
    // 首次 onShow 发生在 tab 路由进行中，守卫 reLaunch 会与其竞态
    // （routeDone with a webviewId xxx is not found），首次守卫延迟到 onReady
    if (!this._authReady) return
    if (!ensureLogin()) return
    this.applyDueFromStorage()
    this.fetchList(true)
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.applyDueFromStorage()
    this.fetchList(true)
  },

  // 首页统计卡下钻：读取一次性过滤条件（switchTab 不能带参，经存储传递）
  applyDueFromStorage() {
    const due = wx.getStorageSync('order_due_filter')
    if (due && DUE_LABEL[due]) {
      this._due = due
      this.setData({ due: due, dueLabel: DUE_LABEL[due], status: '', activeTab: '' })
    }
    wx.removeStorageSync('order_due_filter')
  },

  onPullDownRefresh() {
    this.fetchList(true).then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.list.length < this.data.total) {
      this.fetchList(false)
    }
  },

  async fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: true })
    try {
      const data = await getOrderList({
        page,
        size: this.data.size,
        keyword: this.data.keyword,
        status: this.data.status,
        due: this._due || ''
      })
      const list = ((data && data.list) || []).map((o) =>
        Object.assign({}, o, { statusText: STATUS_TEXT[o.status] || o.status })
      )
      this.setData({
        list: reset ? list : this.data.list.concat(list),
        total: data.total || 0,
        page
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
    this.setData({ loading: false })
  },

  onTabChange(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ status: key, activeTab: key })
    this.fetchList(true)
  },

  clearDue() {
    this._due = ''
    this.setData({ due: '', dueLabel: '' })
    this.fetchList(true)
  },

  onSearch(e) {
    this.setData({ keyword: e.detail.value })
    this.fetchList(true)
  },

  // 页头扫码：扫电梯二维码 → 直接定位关联工单（与首页扫码一致）
  onScan() {
    wx.scanCode({
      onlyFromCamera: true,
      success: (res) => {
        const code = String(res.result || '').trim()
        if (!code) return
        resolveByElevatorCode(code)
          .then((order) => wx.navigateTo({ url: `/pages/order/detail?orderId=${order.id}` }))
          .catch((e) => wx.showModal({ title: '扫码结果', content: e.message || '未找到关联工单', showCancel: false }))
      }
    })
  },

  goDetail(e) {
    wx.navigateTo({ url: `/pages/order/detail?orderId=${e.currentTarget.dataset.id}` })
  },

  // 查看详情 = 电梯详细档案（docs/01 §3.4.1，平台 2.7 自动获取 + 本地维护）
  goProfile(e) {
    wx.navigateTo({ url: `/pages/elevator/profile?elevatorId=${e.currentTarget.dataset.elevatorId}` })
  },

  // 右侧主按钮：待执行→去签到；进行中→继续作业；已完成→查看记录
  onAction(e) {
    const { id, status } = e.currentTarget.dataset
    if (status === 'PENDING') {
      wx.navigateTo({ url: `/pages/order/checkin?orderId=${id}` })
    } else if (status === 'PROCESSING') {
      wx.navigateTo({ url: `/pages/order/checklist?orderId=${id}` })
    } else {
      wx.navigateTo({ url: `/pages/order/detail?orderId=${id}` })
    }
  }
})
