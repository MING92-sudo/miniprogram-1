// 双人作业动态码校验（配合维保人员到场确认）
// 校验通过 → 以配合人员（ASSISTANT）身份直接进入签到流程，动态码一并携带
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
      // 接回签到流程：校验通过后以配合人员身份签到（动态码随签到提交服务端复核）
      wx.redirectTo({
        url: `/pages/order/checkin?orderId=${this.data.orderId}&role=ASSISTANT&dynamicCode=${this.data.code}`
      })
    } catch (e) {
      wx.showToast({ title: e.message || '动态码校验失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
