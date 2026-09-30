// 困人救援登记
const { createRescue } = require('../../services/rescue')

Page({
  data: {
    elevatorCode: '',
    trappedCount: '',
    desc: '',
    submitting: false
  },

  onCodeInput(e) {
    this.setData({ elevatorCode: e.detail.value })
  },

  // 扫电梯二维码自动填入编号（二维码内容 = 纯 elevatorCode，docs/04 约定）
  onScan() {
    wx.scanCode({
      onlyFromCamera: true,
      success: (res) => {
        const code = String(res.result || '').trim()
        if (code) this.setData({ elevatorCode: code })
      }
    })
  },

  onCountInput(e) {
    this.setData({ trappedCount: e.detail.value })
  },

  onDescInput(e) {
    this.setData({ desc: e.detail.value })
  },

  async onSubmit() {
    if (!this.data.elevatorCode) return wx.showToast({ title: '请输入电梯编号', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await createRescue({
        elevatorCode: this.data.elevatorCode,
        trappedCount: Number(this.data.trappedCount) || 0,
        desc: this.data.desc
      })
      wx.showToast({ title: '已登记', icon: 'success' })
      wx.redirectTo({ url: '/pages/rescue/list' })
    } catch (e) {
      wx.showToast({ title: e.message || '登记失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
