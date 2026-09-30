// 困人救援登记（TSG T5002 第五条(四)：直辖市抵达时限 30 分钟，docs/01 §3.9.3）
const { createRescue } = require('../../services/rescue')
const { formatTime } = require('../../utils/util')

Page({
  data: {
    elevatorCode: '',
    trappedCount: '',
    desc: '',
    alarmTime: '', // 接警时间
    departTime: '', // 出动时间
    arriveTime: '', // 抵达时间
    rescuedTime: '', // 解救时间
    reason: '', // 故障原因
    action: '', // 处置措施
    submitting: false
  },

  onLoad() {
    // 接警时间默认当前时刻（登记即接警场景），可修改
    this.setData({ alarmTime: formatTime().slice(11, 16) })
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

  onTimeChange(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value })
  },

  onReasonInput(e) {
    this.setData({ reason: e.detail.value })
  },

  onActionInput(e) {
    this.setData({ action: e.detail.value })
  },

  async onSubmit() {
    if (!this.data.elevatorCode) return wx.showToast({ title: '请输入电梯编号', icon: 'none' })
    if (!this.data.alarmTime) return wx.showToast({ title: '请填写接警时间', icon: 'none' })
    if (!this.data.arriveTime) return wx.showToast({ title: '请填写抵达时间（法定时限节点）', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const today = formatTime().slice(0, 10)
      await createRescue({
        elevatorCode: this.data.elevatorCode,
        trappedCount: Number(this.data.trappedCount) || 0,
        desc: this.data.desc,
        // 救援四节点（docs/01 §3.9.3.1 强制记录项，系统自动计算各节点耗时并留痕）
        alarmAt: today + ' ' + this.data.alarmTime + ':00',
        departAt: this.data.departTime ? today + ' ' + this.data.departTime + ':00' : '',
        arriveAt: today + ' ' + this.data.arriveTime + ':00',
        rescuedAt: this.data.rescuedTime ? today + ' ' + this.data.rescuedTime + ':00' : '',
        reason: this.data.reason,
        action: this.data.action
      })
      wx.showToast({ title: '已登记', icon: 'success' })
      wx.redirectTo({ url: '/pages/rescue/list' })
    } catch (e) {
      wx.showToast({ title: e.message || '登记失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
