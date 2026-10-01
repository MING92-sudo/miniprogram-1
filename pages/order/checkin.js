// 签到流程：定位 → 水印自拍 → 提交
// 错误码约定：1001 定位超阈（申诉）、1003 工单锁定；1006/1007 为配置类拦截（docs/04 A.0）
const { checkin, getOrderDetail, requestEvidence } = require('../../services/order')
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
    evidence: null, // 服务端签发的取证令牌（水印与维保记录均以令牌内值为准）
    evidenceError: '',
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

  // 定位成功后：先向服务端换取证令牌（服务端在此校验地理围栏），
  // 再用**已校验的坐标**做逆地址解析，避免水印地址来自未验证坐标
  async resolveLocation(location) {
    this.setData({ location, locationText: '已获取坐标，正在核验作业地点...' })
    await this.loadEvidence(location)
  },

  async loadEvidence(location) {
    try {
      const evidence = await requestEvidence(this.data.orderId, {
        latitude: location.latitude,
        longitude: location.longitude,
        locationAccuracy: location.accuracy || 0
      })
      this.setData({ evidence, evidenceError: '' })
      // reverseGeocode 永不reject，失败/超时自动回退坐标
      const address = await reverseGeocode(evidence.latitude, evidence.longitude)
      const isCoord = /^[\d.,\s]+$/.test(address)
      this.setData({ addressText: isCoord ? '' : address, locationText: address })
    } catch (e) {
      this.setData({ evidence: null, evidenceError: (e && e.message) || '取证失败' })
      if (e && e.code === 1001) {
        wx.showModal({
          title: '签到被拦截',
          content: (e.message || '签到位置超出允许范围') + '。如确属到场，可提交申诉。',
          showCancel: false
        })
      } else {
        wx.showToast({ title: (e && e.message) || '取证失败，请重试', icon: 'none' })
      }
    }
  },

  // 水印自拍：跳转水印相机页。水印文本一律由服务端取证令牌载荷渲染
  //（时间/坐标/工单号），相机页不接受任何坐标/时间参数
  goCamera() {
    const ev = this.data.evidence
    if (!ev) return wx.showToast({ title: '取证未完成，请稍候重试', icon: 'none' })
    wx.navigateTo({
      url: '/pages/common/watermark-camera?from=checkin&evidence=' + encodeURIComponent(ev.token)
    })
  },

  // 由水印相机页面回传照片（取证令牌已在拍照前换取并随签到提交，此处无需回传）
  onPhotoReady(photo) {
    this.setData({ photo })
  },

  removePhoto() {
    this.setData({ photo: '' })
  },

  async onSubmit() {
    if (!this.data.location) return wx.showToast({ title: '请先获取定位', icon: 'none' })
    if (!this.data.evidence) {
      return wx.showToast({ title: this.data.evidenceError || '取证未完成，请稍候重试', icon: 'none' })
    }
    if (!this.data.photo) return wx.showToast({ title: '请完成水印自拍', icon: 'none' })
    if (this.data.role === 'ASSISTANT' && !this.data.dynamicCode) {
      return wx.showToast({ title: '配合人员须输入主维保动态码', icon: 'none' })
    }
    this.setData({ submitting: true })
    try {
      const uploaded = await uploadImage(this.data.photo)
      // 坐标与时间由服务端从取证令牌中取（令牌内已绑定并通过围栏校验），
      // 此处不回传 latitude/longitude；collectedAt 仅用于离线对账留痕
      const payload = {
        evidenceToken: this.data.evidence.token,
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
