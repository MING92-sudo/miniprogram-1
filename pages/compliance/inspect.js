// 自行检查台账（TSG T5002 第五条(九)：每台每年至少 1 次，须在下次定期检验前完成）
const { getInspects } = require('../../services/compliance')

Page({
  data: {
    list: []
  },

  onShow() {
    this.fetchList()
  },

  async fetchList() {
    try {
      const data = await getInspects()
      this.setData({ list: data || [] })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  goRun(e) {
    const item = this.data.list[e.currentTarget.dataset.index]
    if (item.status === '已完成') {
      return wx.showToast({ title: '本年度已检查（' + item.lastInspectDate + '）', icon: 'none' })
    }
    wx.navigateTo({
      url: `/pages/compliance/inspect-run?elevatorId=${item.elevatorId}&elevatorName=${encodeURIComponent(item.elevatorName)}`
    })
  }
})
