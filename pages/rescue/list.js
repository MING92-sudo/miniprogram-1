// 救援列表
const { getRescueList } = require('../../services/rescue')

Page({
  data: {
    list: [],
    page: 1,
    size: 20,
    total: 0
  },

  onShow() {
    this.fetchList(true)
  },

  onPullDownRefresh() {
    this.fetchList(true).then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.list.length < this.data.total) this.fetchList(false)
  },

  async fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    try {
      const data = await getRescueList({ page, size: this.data.size })
      this.setData({
        list: reset ? data.list || [] : this.data.list.concat(data.list || []),
        total: data.total || 0,
        page
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  goCreate() {
    wx.navigateTo({ url: '/pages/rescue/create' })
  }
})
