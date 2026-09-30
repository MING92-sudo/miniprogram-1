// 签到流程：定位 → 水印自拍 → 提交
// 错误码约定：1001 定位超阈（申诉）、1003 工单锁定、1006/1007 排班拦截
const config = require('../../config/index')
const { checkin, getOrderDetail } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { reverseGeocode } = require('../../services/location')
const { formatTime } = require('../../utils/util')

// 演示定位兜底（真机授权失败时使用，保证 Mock 流程可走通）
const FALLBACK_LOCATION = { latitude: 29.7192, longitude: 106.6337, accuracy: 25 }

Page({
  data: {
    orderId: '',
    elevatorName: '',
    location: null,
    locationText: '',
    addressText: '',
    photo: '',
    submitting: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
    this.fetchOrder()
    this.getLocation()
  },

  async fetchOrder() {
    try {
      const order = await getOrderDetail(this.data.orderId)
      this.setData({
        elevatorName: (order.elevator && order.elevator.elevatorName) || ''
      })
    } catch (e) {
      // 详情加载失败不阻断签到流程
    }
  },

  getLocation() {
    this.setData({ locationText: '定位获取中...' })
    // 防卡死兜底：wx.getLocation 在隐私协议未配置/权限异常等场景可能回调不触发，
    // 超时 8s 未返回则使用演示定位继续流程（回调只处理一次）
    let settled = false
    const settle = (res) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolveLocation(res)
    }
    const timer = setTimeout(() => settle(FALLBACK_LOCATION), 8000)
    const resolveLocation = (loc) => this.resolveLocation(loc)
    wx.getLocation({
      type: 'gcj02',
      success: (res) => settle({
        latitude: res.latitude,
        longitude: res.longitude,
        accuracy: res.accuracy || 0 // 定位精度（米），签到三要素之一
      }),
      fail: () => settle(FALLBACK_LOCATION) // 开发工具/未授权场景：使用演示定位继续流程
    })
  },

  // 定位成功后逆地址解析出具体位置文字（省市区/道路/POI）
  async resolveLocation(location) {
    // 先换掉"定位获取中"文案，避免解析期间界面停留在误导性的中间状态
    this.setData({ location, locationText: '已获取坐标，解析地址中...' })
    // reverseGeocode 永不 reject，失败/超时自动回退坐标
    const address = await reverseGeocode(location.latitude, location.longitude)
    const isCoord = /^[\d.,\s]+$/.test(address)
    this.setData({ addressText: isCoord ? '' : address, locationText: address })
  },

  // 水印自拍：跳转水印相机页，回传临时文件路径（附带具体位置用于水印）
  goCamera() {
    const loc = this.data.location
    let qs = ''
    if (this.data.addressText) qs += `&address=${encodeURIComponent(this.data.addressText)}`
    if (loc) qs += `&lat=${loc.latitude}&lng=${loc.longitude}`
    wx.navigateTo({ url: `/pages/common/watermark-camera?from=checkin&orderId=${this.data.orderId}${qs}` })
  },

  // 由水印相机页面回传
  onPhotoReady(photo) {
    this.setData({ photo })
  },

  removePhoto() {
    this.setData({ photo: '' })
  },

  async onSubmit() {
    if (!this.data.location) return wx.showToast({ title: '请先获取定位', icon: 'none' })
    if (!this.data.photo) return wx.showToast({ title: '请完成水印自拍', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const uploaded = await uploadImage(this.data.photo)
      // 字段对齐 docs/04 A.2：定位三要素 + 角色 + 本地采集时间（离线补传时为原始时间戳）
      await checkin(this.data.orderId, {
        latitude: this.data.location.latitude,
        longitude: this.data.location.longitude,
        locationAccuracy: this.data.location.accuracy || 0,
        photoFileId: uploaded.fileId,
        role: 'PRINCIPAL', // 演示账号为主维保；配合人员（ASSISTANT）须携带双人动态码
        collectedAt: formatTime()
      })
      wx.showToast({ title: '签到成功', icon: 'success' })
      wx.redirectTo({ url: `/pages/order/checklist?orderId=${this.data.orderId}` })
    } catch (e) {
      if (e.code === 1001) {
        // 定位超阈 → 引导申诉
        wx.showModal({
          title: '签到被拦截',
          content: '签到位置超出允许范围。' + (e.message || ''),
          confirmText: '知道了',
          showCancel: false
        })
      } else {
        wx.showToast({ title: e.message || '签到失败', icon: 'none' })
      }
    }
    this.setData({ submitting: false })
  }
})
