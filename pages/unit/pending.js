// 使用单位 · 待确认列表（安全管理员）
const { getPendingRecords } = require('../../services/unit')

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
      const data = await getPendingRecords({ page, size: this.data.size })
      this.setData({
        list: reset ? data.list || [] : this.data.list.concat(data.list || []),
        total: data.total || 0,
        page
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  goConfirm(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/unit/confirm?id=${id}` })
  },

  // 查看维保记录详情（签到/签退时间、检查结果等）
  goRecord(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/unit/record?id=${id}` })
  }
})
