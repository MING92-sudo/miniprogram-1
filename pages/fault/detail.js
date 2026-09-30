// 故障详情 / 闭环处理
const { getFaultDetail, closeFault } = require('../../services/fault')

Page({
  data: {
    id: '',
    fault: null,
    loading: true,
    handleDesc: '',
    submitting: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
    this.fetchDetail()
  },

  async fetchDetail() {
    try {
      const fault = await getFaultDetail(this.data.id)
      this.setData({ fault, loading: false })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onHandleInput(e) {
    this.setData({ handleDesc: e.detail.value })
  },

  async onClose() {
    if (!this.data.handleDesc) return wx.showToast({ title: '请填写处理结果', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await closeFault(this.data.id, { handleDesc: this.data.handleDesc })
      wx.showToast({ title: '已闭环', icon: 'success' })
      this.fetchDetail()
    } catch (e) {
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
