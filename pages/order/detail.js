// 工单详情 / 扫码结果页
// 入参：orderId 或 elevatorCode（扫码进入）
// 按状态显示操作：PENDING→去签到；PROCESSING→继续作业/双人动态码；DONE→只读
const { getOrderDetail, resolveByElevatorCode } = require('../../services/order')
const { STATUS_TEXT, REPORT_STATUS_TEXT } = require('../../constants/index')

Page({
  data: {
    order: null,
    elevator: null,
    statusText: '',
    reportStatusText: '',
    loading: true
  },

  onLoad(query) {
    if (query.orderId) {
      this.fetchOrder(query.orderId)
    } else if (query.elevatorCode) {
      this.resolveElevator(decodeURIComponent(query.elevatorCode))
    }
  },

  // 从签到/清单页返回后刷新状态
  onShow() {
    if (this.data.order && this.data.order.id) {
      this.fetchOrder(this.data.order.id)
    }
  },

  async fetchOrder(orderId) {
    try {
      const order = await getOrderDetail(orderId)
      this.applyOrder(order)
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  applyOrder(order) {
    this.setData({
      order,
      elevator: order.elevator || null,
      statusText: STATUS_TEXT[order.status] || order.status,
      reportStatusText: order.reportStatus
        ? REPORT_STATUS_TEXT[order.reportStatus] || order.reportStatus
        : '',
      loading: false
    })
  },

  // 扫码 → 通过 elevatorCode 找关联工单（1004/1005 等错误码由后端拦截）
  async resolveElevator(elevatorCode) {
    try {
      const data = await resolveByElevatorCode(elevatorCode)
      this.applyOrder(data)
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '未找到关联工单', icon: 'none' })
    }
  },

  goCheckin() {
    wx.navigateTo({ url: `/pages/order/checkin?orderId=${this.data.order.id}` })
  },

  goChecklist() {
    wx.navigateTo({ url: `/pages/order/checklist?orderId=${this.data.order.id}` })
  },

  goDynamicCode() {
    wx.navigateTo({ url: `/pages/order/dynamic-code?orderId=${this.data.order.id}` })
  }
})
