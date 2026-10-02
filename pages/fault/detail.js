// 急修单详情：完整单据查看 + 维修闭环（处理结果 + 使用单位安全管理员签字，docs/01 §3.9.2）
const { getFaultDetail, closeFault } = require('../../services/fault')
const { uploadImage } = require('../../services/upload')

const STATUS_TEXT = {
  OPEN: '处理中',
  CLOSED: '已闭环'
}

Page({
  data: {
    id: '',
    fault: null,
    statusText: '',
    loading: true,
    result: '',
    signaturePath: '',
    submitting: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
    this.fetchDetail()
  },

  async fetchDetail() {
    try {
      const fault = await getFaultDetail(this.data.id)
      this.setData({ fault, statusText: STATUS_TEXT[fault.status] || fault.status, loading: false })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onResultInput(e) {
    this.setData({ result: e.detail.value })
  },

  onPreviewPhoto(e) {
    const urls = this.data.fault.photos || []
    wx.previewImage({ urls, current: e.currentTarget.dataset.url })
  },

  onPreviewSign() {
    wx.previewImage({ urls: [this.data.fault.signature] })
  },

  // 使用单位安全管理员现场签字（签名板导出图片回传）
  onSign() {
    if (!this.data.result) return wx.showToast({ title: '请先填写处理结果', icon: 'none' })
    wx.navigateTo({
      url: '/pages/common/signature?from=fault&id=' + this.data.id,
      events: {
        signatureDone: (data) => this.onSignatureReady(data)
      }
    })
  },

  // 兜底通道：签名页直接调上一页方法
  onSignatureReady(data) {
    if (!data || !data.path) return
    this.setData({ signaturePath: data.path })
    this.submitClose()
  },

  async submitClose() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    try {
      wx.showLoading({ title: '提交中' })
      const up = await uploadImage(this.data.signaturePath)
      await closeFault(this.data.id, { result: this.data.result, signature: up.url })
      wx.hideLoading()
      wx.showToast({ title: '急修单已闭环', icon: 'success' })
      this.setData({ signaturePath: '' })
      this.fetchDetail()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
