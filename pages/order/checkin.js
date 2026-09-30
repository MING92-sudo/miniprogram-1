// 签到流程：定位 → 水印自拍 → 提交
// 错误码约定：1001 定位超阈（申诉）、1003 工单锁定、1006/1007 排班拦截
const { checkin, getOrderDetail } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { reverseGeocode } = require('../../services/location')
const { formatTime } = require('../../utils/util')

// 角色（docs/04 A.2）：主维保 PRINCIPAL / 配合人员 ASSISTANT
const ROLES = [
  { value: 'PRINCIPAL', label: '主维保人' },
  { value: 'ASSISTANT', label: '配合人员' }
]

Page({
  data: {
    orderId: '',
    elevatorName: '',
    roles: ROLES,
    role: 'PRINCIPAL', // 双人作业：配合人员须携带主维保动态码
    dynamicCode: '',
    location: null,
    locationText: '',
    addressText: '',
    locateFailed: false, // 定位失败如实提示（合规：严禁伪造坐标兜底）
    photo: '',
    submitting: false
  },

  onLoad(query) {
    // 从双人动态码校验页跳转而来时，直接以配合人员身份进入
    const role = query.role === 'ASSISTANT' ? 'ASSISTANT' : 'PRINCIPAL'
    this.setData({
      orderId: query.orderId || '',
      role,
      dynamicCode: query.dynamicCode || ''
    })
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

  onRoleChange(e) {
    this.setData({ role: e.currentTarget.dataset.value })
  },

  onCodeInput(e) {
    this.setData({ dynamicCode: e.detail.value })
  },

  getLocation() {
    this.setData({ locateFailed: false, locationText: '定位获取中...' })
    // 防卡死兜底：wx.getLocation 在隐私协议未配置/权限异常等场景可能回调不触发，
    // 超时 8s 未返回则按定位失败处理（回调只处理一次）。
    // 合规约定（docs/08 P1）：定位失败必须如实提示并重试，严禁伪造坐标兜底。
    let settled = false
    const settle = (res) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (res) this.resolveLocation(res)
      else this.locateFail()
    }
    const timer = setTimeout(() => settle(null), 8000)
    wx.getLocation({
      type: 'gcj02',
      success: (res) => settle({
        latitude: res.latitude,
        longitude: res.longitude,
        accuracy: res.accuracy || 0 // 定位精度（米），签到三要素之一
      }),
      fail: () => settle(null)
    })
  },

  locateFail() {
    this.setData({
      locateFailed: true,
      locationText: '定位失败，请检查定位权限或网络后重试'
    })
    wx.showToast({ title: '定位失败，请重试', icon: 'none' })
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
    if (this.data.role === 'ASSISTANT' && !this.data.dynamicCode) {
      return wx.showToast({ title: '配合人员须输入主维保动态码', icon: 'none' })
    }
    this.setData({ submitting: true })
    try {
      const uploaded = await uploadImage(this.data.photo)
      // 字段对齐 docs/04 A.2：定位三要素 + 角色 + 动态码 + 本地采集时间（离线补传时为原始时间戳）
      const payload = {
        latitude: this.data.location.latitude,
        longitude: this.data.location.longitude,
        locationAccuracy: this.data.location.accuracy || 0,
        photoFileId: uploaded.fileId,
        role: this.data.role,
        collectedAt: formatTime()
      }
      // docs/04 A.2：role=ASSISTANT 时 dynamicCode 必传，服务端校验有效期/工单匹配
      if (this.data.role === 'ASSISTANT') payload.dynamicCode = this.data.dynamicCode
      await checkin(this.data.orderId, payload)
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
