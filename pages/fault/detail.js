// 急修单详情：完整单据查看 + 维修闭环（处理结果 + 使用单位安全管理员签字，docs/01 §3.9.2）
const { getFaultDetail, updateFault, closeFault } = require('../../services/fault')
const { uploadImage } = require('../../services/upload')
const { formatTime, ok } = require('../../utils/util')

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
    siteDesc: '',
    todoDesc: '',
    finishedAt: '',
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
      this.setData({
        fault,
        statusText: STATUS_TEXT[fault.status] || fault.status,
        siteDesc: fault.siteDesc || '',
        todoDesc: fault.todoDesc || '',
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onResultInput(e) {
    this.setData({ result: e.detail.value })
  },

  onSiteInput(e) {
    this.setData({ siteDesc: e.detail.value })
  },

  onTodoInput(e) {
    this.setData({ todoDesc: e.detail.value })
  },

  // 保存维修过程字段（现场情况/待办事项/处理结果/维修结束时间）
  async onSaveProcess() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    try {
      await updateFault(this.data.id, {
        siteDesc: this.data.siteDesc,
        todoDesc: this.data.todoDesc,
        handleDesc: this.data.result,
        finishedAt: require('../../utils/util').formatTime()
      })
      ok('维修过程已保存')
    } catch (e) {
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    }
    this.setData({ submitting: false })
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
