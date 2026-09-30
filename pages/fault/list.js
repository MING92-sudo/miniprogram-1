// 故障记录列表：状态筛选 + 详情闭环跟踪
const { getFaultList } = require('../../services/fault')
const { ensureLogin } = require('../../utils/guard')

const STATUS_TEXT = {
  OPEN: '处理中',
  CLOSED: '已闭环'
}

const TABS = [
  { key: '', label: '全部' },
  { key: 'OPEN', label: '处理中' },
  { key: 'CLOSED', label: '已闭环' }
]

Page({
  data: {
    tabs: TABS,
    activeTab: '',
    list: [],
    page: 1,
    size: 20,
    total: 0,
    status: '',
    loading: false
  },

  onShow() {
    // 首次 onShow 发生在路由进行中，守卫 reLaunch 会与其竞态，首次守卫延迟到 onReady
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
    if (this.data.list.length < this.data.total) this.fetchList(false)
  },

  onTabChange(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ status: key, activeTab: key })
    this.fetchList(true)
  },

  async fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: true })
    try {
      const data = await getFaultList({
        page,
        size: this.data.size,
        status: this.data.status
      })
      const list = ((data && data.list) || []).map((f) =>
        Object.assign({}, f, { statusText: STATUS_TEXT[f.status] || f.status })
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

  goDetail(e) {
    wx.navigateTo({ url: `/pages/fault/detail?id=${e.currentTarget.dataset.id}` })
  },

  goReport() {
    wx.navigateTo({ url: '/pages/fault/report' })
  }
})
