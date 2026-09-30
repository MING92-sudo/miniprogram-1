// 工单列表：历史工单检索 + 状态筛选
const { getOrderList } = require('../../services/order')
const { STATUS_TEXT } = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

const TABS = [
  { key: '', label: '全部' },
  { key: 'PENDING', label: '待执行' },
  { key: 'PROCESSING', label: '进行中' },
  { key: 'DONE', label: '已完成' }
]

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
    loading: false
  },

  onShow() {
    // 首次 onShow 发生在 tab 路由进行中，守卫 reLaunch 会与其竞态
    // （routeDone with a webviewId xxx is not found），首次守卫延迟到 onReady
    if (!this._authReady) return
    if (!ensureLogin()) return
    this.fetchList(true)
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.fetchList(true)
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
        status: this.data.status
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

  onSearch(e) {
    this.setData({ keyword: e.detail.value })
    this.fetchList(true)
  },

  goDetail(e) {
    wx.navigateTo({ url: `/pages/order/detail?orderId=${e.currentTarget.dataset.id}` })
  }
})
