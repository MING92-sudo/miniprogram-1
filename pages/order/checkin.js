// 签到流程：定位（实时距离三态）→ 水印自拍 → 提交
// 错误码约定：1001 定位超阈（服务端拦截，定位申诉审核流已裁 docs/01 §10.2）、1003 状态/动态码类拦截；
// 1006/1007 为配置类拦截（docs/04 A.0.1）
const { checkin, getOrderDetail, issueDynamicCode } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { reverseGeocode } = require('../../services/location')
const { formatTime, distanceMeters } = require('../../utils/util')
const { getUserInfo } = require('../../utils/auth')

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
    identityText: '',   // 按登录账号自动判定的签到身份（docs/03 §3.2 项5）
    roleLocked: false,  // 身份与登录账号匹配时锁定，不允许手选
    isDual: false,
    issuedCode: '',
    codeCountdown: 5,
    location: null,
    locationText: '',
    addressText: '',
    locateFailed: false, // 定位失败如实提示（合规：严禁伪造坐标兜底）
    elevatorGeo: null,   // 电梯档案坐标与阈值（docs/02 §5.4）
    geoState: '',        // OK / NEAR / OVER / UNKNOWN
    geoText: '',
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
      const el = order.elevator || {}
      this.setData({
        elevatorName: el.elevatorName || '',
        elevatorGeo: {
          lat: Number(el.lat) || null,
          lng: Number(el.lng) || null,
          // 电梯级阈值优先，缺省用全局默认 200 米（后端同口径，docs/02 §5.4）
          threshold: Number(el.checkinThreshold) || 200
        }
      })
      this.resolveIdentity(order)
      this.updateGeoState()
    } catch (e) {
      // 详情加载失败不阻断签到流程
    }
  },

  // 按登录账号自动判定签到身份：主维保 → 显示动态码供配合人员输入；配合人员 → 输入动态码
  resolveIdentity(order) {
    // 从双人动态码校验页携码进入时保持配合人员身份
    if (this.data.role === 'ASSISTANT' && this.data.dynamicCode) {
      this.setData({ roleLocked: true, isDual: true, identityText: '配合人员（已携主维保动态码）' })
      return
    }
    const me = getUserInfo() || {}
    const isDual = !!order.assistantName
    const isPrincipal = !!order.workerName &&
      (me.name === order.workerName ||
        (!!order.workerPlatformId && me.platformId === order.workerPlatformId))
    const isAssistant = !!order.assistantName &&
      (me.name === order.assistantName ||
        (!!order.assistantPlatformId && me.platformId === order.assistantPlatformId))
    if (!isDual) {
      this.setData({ role: 'PRINCIPAL', roleLocked: true, isDual: false, identityText: '单人作业 · 主维保人' })
    } else if (isPrincipal) {
      this.setData({ role: 'PRINCIPAL', roleLocked: true, isDual: true, identityText: '主维保人（下方动态码供配合人员输入）' })
      this.startCodeTimer()
    } else if (isAssistant) {
      this.setData({ role: 'ASSISTANT', roleLocked: true, isDual: true, identityText: '配合人员（请输入主维保动态码）' })
    } else {
      this.setData({ roleLocked: false, isDual: true, identityText: '当前账号不在该工单人员名单，请选择签到身份' })
    }
  },

  // 主维保身份：内联生成动态码（5 秒步长轮换，与双人动态码页同口径）
  startCodeTimer() {
    this.stopCodeTimer()
    this.refreshCode()
    this._codeTimer = setInterval(() => {
      const left = this.data.codeCountdown - 1
      if (left <= 0) this.refreshCode()
      else this.setData({ codeCountdown: left })
    }, 1000)
  },

  stopCodeTimer() {
    if (this._codeTimer) {
      clearInterval(this._codeTimer)
      this._codeTimer = null
    }
  },

  async refreshCode() {
    try {
      const r = await issueDynamicCode(this.data.orderId)
      this.setData({ issuedCode: r.code, codeCountdown: r.stepSeconds || 5 })
    } catch (e) {
      this.stopCodeTimer()
    }
  },

  onHide() {
    this.stopCodeTimer()
  },

  onUnload() {
    this.stopCodeTimer()
  },

  // 实时距离三态（docs/03 §3.2 项5）：范围内 / 接近阈值 / 超出阈值；
  // 电梯坐标未登记 → UNKNOWN，只留痕不拦截（docs/04 A.0.1 码表 1005）。前端仅提示，拦截以服务端为准。
  updateGeoState() {
    const loc = this.data.location
    if (!loc) return
    const geo = this.data.elevatorGeo
    if (!geo || !geo.lat || !geo.lng) {
      this.setData({ geoState: 'UNKNOWN', geoText: '电梯坐标未登记：本次签到仅留痕，不做距离校验' })
      return
    }
    const dist = distanceMeters(loc.latitude, loc.longitude, geo.lat, geo.lng)
    if (dist < 0) {
      this.setData({ geoState: 'UNKNOWN', geoText: '距离暂不可计算，签到以后端校验为准' })
      return
    }
    const d = Math.round(dist * 10) / 10
    const th = geo.threshold
    if (d > th) {
      this.setData({ geoState: 'OVER', geoText: '距电梯 ' + d + ' 米，超出阈值 ' + th + ' 米：请到现场后签到' })
    } else if (d > th * 0.8) {
      this.setData({ geoState: 'NEAR', geoText: '距电梯 ' + d + ' 米，接近阈值 ' + th + ' 米' })
    } else {
      this.setData({ geoState: 'OK', geoText: '距电梯 ' + d + ' 米（阈值 ' + th + ' 米），在允许范围内' })
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
    this.updateGeoState()
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
    // 超阈先在前端拦截（服务端仍会返回 1001）：定位失败如实提示，严禁伪造坐标兜底
    if (this.data.geoState === 'OVER') {
      return wx.showModal({
        title: '签到位置超出允许范围',
        content: this.data.geoText + '。请移动到电梯现场后重新定位；严禁伪造坐标（docs/01 §10.1 合规底线）。',
        showCancel: false,
        confirmText: '知道了'
      })
    }
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
        // 定位超阈（服务端 Haversine 校验）：定位申诉审核流已裁剪（docs/01 §10.2），如实提示并引导到现场重试
        wx.showModal({
          title: '签到被拦截',
          content: '服务端判定签到位置超出允许范围：' + (e.message || '') + '。请到现场后重新定位签到。',
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
