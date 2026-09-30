// 双人作业动态码校验（配合维保人员到场确认）
const { verifyDynamicCode } = require('../../services/order')

Page({
  data: {
    orderId: '',
    code: '',
    submitting: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
  },

  onCodeInput(e) {
    this.setData({ code: e.detail.value })
  },

  async onSubmit() {
    if (!this.data.code) return wx.showToast({ title: '请输入动态码', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await verifyDynamicCode(this.data.orderId, { code: this.data.code })
      wx.showToast({ title: '校验通过', icon: 'success' })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || '动态码校验失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
