// 使用单位 · 确认（手写签名 + 满意度评价）
const { confirmRecord } = require('../../services/unit')
const { uploadImage } = require('../../services/upload')

Page({
  data: {
    id: '',
    signature: '',
    satisfaction: 0,
    submitting: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
  },

  goSignature() {
    wx.navigateTo({
      url: `/pages/common/signature?from=unit-confirm&id=${this.data.id}`,
      events: {
        signatureDone: (data) => {
          this.setData({ signature: data.path })
        }
      }
    })
  },

  onSatisfaction(e) {
    this.setData({ satisfaction: Number(e.currentTarget.dataset.value) })
  },

  async onSubmit() {
    if (!this.data.signature) return wx.showToast({ title: '请完成签名', icon: 'none' })
    if (!this.data.satisfaction) return wx.showToast({ title: '请评价满意度', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const sig = await uploadImage(this.data.signature)
      await confirmRecord(this.data.id, {
        signatureFileId: sig.fileId,
        signatureUrl: this.data.signature, // mock 演示回显；真实后端忽略
        satisfaction: this.data.satisfaction
      })
      wx.showToast({ title: '确认成功', icon: 'success' })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || '确认失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
