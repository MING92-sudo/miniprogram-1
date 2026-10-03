// 工单详情 / 扫码结果页
// 入参：orderId 或 elevatorCode（扫码进入）
// 按状态与"本人是否已签到"显示操作：未签到→去签到（主维保/配合人员分别签到，docs/01 §3.7.2）；
// 已签到→继续作业清单；双人工单提供动态码入口；DONE→只读
const { getOrderDetail, resolveByElevatorCode, retryRecordUpload } = require('../../services/order')
const { STATUS_TEXT, REPORT_STATUS_TEXT, CHECK_RESULT_TEXT } = require('../../constants/index')
const { getUserInfo } = require('../../utils/auth')

Page({
  data: {
    order: null,
    elevator: null,
    statusText: '',
    reportStatusText: '',
    canReupload: false,
    loading: true,
    isDual: false,       // 双人工单（有配合人员）
    isAssistant: false,  // 当前登录人是配合人员
    myCheckedIn: false,  // 本人是否已签到（主维保/配合人员各自一条留痕）
    canCheckin: false
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
    // 双人分别签到：按当前登录人比对主维保/配合人员，决定签到入口与动态码模式
    const me = getUserInfo() || {}
    const isAssistant = !!order.assistantName &&
      (me.name === order.assistantName ||
        (!!order.assistantPlatformId && me.platformId === order.assistantPlatformId))
    const myCheckedIn = isAssistant ? !!order.assistantCheckedIn : !!order.principalCheckedIn
    this.setData({
      order,
      elevator: order.elevator || null,
      isDual: !!order.assistantName,
      isAssistant: isAssistant,
      myCheckedIn: myCheckedIn,
      canCheckin: order.status !== 'DONE' && !myCheckedIn,
      statusText: STATUS_TEXT[order.status] || order.status,
      reportStatusText: order.reportStatus
        ? REPORT_STATUS_TEXT[order.reportStatus] || order.reportStatus
        : '',
      canReupload: order.reportStatus === 'FAILED',
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
    const role = this.data.isAssistant ? 'ASSISTANT' : 'PRINCIPAL'
    wx.navigateTo({ url: `/pages/order/checkin?orderId=${this.data.order.id}&role=${role}` })
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

  // 主维保→生成动态码；配合人员→输入校验（docs/03 §3.2 项6）
  goDynamicCode() {
    const mode = this.data.isAssistant ? 'INPUT' : 'ISSUE'
    wx.navigateTo({ url: `/pages/order/dynamic-code?orderId=${this.data.order.id}&mode=${mode}` })
  },

  // 维保记录预览页（docs/03 V2.0 §3.2 #12：与使用单位确认同源）
  goRecordPreview() {
    const info = this.data.order.recordInfo || {}
    if (!info.id) return
    wx.navigateTo({ url: '/pages/order/record-preview?recordId=' + info.id })
  },

  // 手动重报平台 2.6（仅 FAILED；后端拦截非 FAILED 记录）
  async retryReport() {
    const info = this.data.order.recordInfo || {}
    const recordId = info.id
    if (!recordId) return
    try {
      const res = await retryRecordUpload(recordId)
      this.setData({ 'order.reportStatus': res.reportStatus || 'REPORTED', canReupload: false })
      wx.showToast({ title: '重报成功', icon: 'success' })
      this.fetchOrder(this.data.order.id)
    } catch (e) {
      wx.showToast({ title: e.message || '重报失败', icon: 'none' })
    }
  }
})
