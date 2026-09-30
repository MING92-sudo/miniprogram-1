// 救援详情
const { getRescueDetail } = require('../../services/rescue')

Page({
  data: {
    id: '',
    record: null,
    loading: true
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
    this.fetchDetail()
  },

  async fetchDetail() {
    try {
      const record = await getRescueDetail(this.data.id)
      this.setData({ record, loading: false })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  }
})
