// 工单详情 / 扫码结果页
// 入参：orderId 或 elevatorCode（扫码进入）
// 按状态显示操作：PENDING→去签到；PROCESSING→继续作业/双人动态码；DONE→只读
const { getOrderDetail, resolveByElevatorCode } = require('../../services/order')
const { STATUS_TEXT, REPORT_STATUS_TEXT, CHECK_RESULT_TEXT } = require('../../constants/index')

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
    // DONE：组装完整维保记录视图（检查项结果 / 现场照片 / 双签字 / 确认状态）
    const recordItems = (order.checklist || []).map(function (i) {
      const photos = i.photos || []
      const remark = i.abnormalDesc || i.valueText ||
        (i.value != null ? '读数 ' + i.value + (i.valueUnit || '') : '') ||
        i.skipReason || (photos.length ? '照片 ' + photos.length + ' 张' : '')
      return Object.assign({}, i, {
        resultText: i.result ? (CHECK_RESULT_TEXT[i.result] || i.result) : '',
        remark: remark
      })
    })
    const recordPhotos = []
    recordItems.forEach(function (i) {
      (i.photos || []).forEach(function (p) { recordPhotos.push(p) })
    })
    this.setData({
      order,
      elevator: order.elevator || null,
      statusText: STATUS_TEXT[order.status] || order.status,
      reportStatusText: order.reportStatus
        ? REPORT_STATUS_TEXT[order.reportStatus] || order.reportStatus
        : '',
      recordItems: recordItems,
      recordPhotos: recordPhotos,
      loading: false
    })
  },

  // 维保记录照片预览
  previewRecordPhoto(e) {
    wx.previewImage({
      current: e.currentTarget.dataset.src,
      urls: this.data.recordPhotos
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

  // 安全管理员签字确认页（本机代签或微信分享远程签字）
  goSignConfirm() {
    const info = this.data.order.recordInfo || {}
    wx.navigateTo({
      url: `/pages/unit/sign?rid=${info.id}&token=${info.shareToken}`
    })
  },

  goChecklist() {
    wx.navigateTo({ url: `/pages/order/checklist?orderId=${this.data.order.id}` })
  },

  goDynamicCode() {
    wx.navigateTo({ url: `/pages/order/dynamic-code?orderId=${this.data.order.id}` })
  }
})
