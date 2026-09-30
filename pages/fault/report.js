// 故障上报
const { reportFault } = require('../../services/fault')

Page({
  data: {
    elevatorCode: '',
    faultType: '',
    faultTypes: ['门系统', '曳引系统', '电气故障', '平层异常', '其他'],
    desc: '',
    submitting: false
  },

  onCodeInput(e) {
    this.setData({ elevatorCode: e.detail.value })
  },

  onTypeChange(e) {
    this.setData({ faultType: this.data.faultTypes[Number(e.detail.value)] })
  },

  onDescInput(e) {
    this.setData({ desc: e.detail.value })
  },

  async onSubmit() {
    if (!this.data.elevatorCode) return wx.showToast({ title: '请输入电梯编号', icon: 'none' })
    if (!this.data.faultType) return wx.showToast({ title: '请选择故障类型', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const fault = await reportFault({
        elevatorCode: this.data.elevatorCode,
        faultType: this.data.faultType,
        desc: this.data.desc
      })
      wx.showToast({ title: '已上报', icon: 'success' })
      // 跳转详情页可继续跟踪闭环
      wx.redirectTo({ url: `/pages/fault/detail?id=${fault.id}` })
    } catch (e) {
      wx.showToast({ title: e.message || '上报失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
