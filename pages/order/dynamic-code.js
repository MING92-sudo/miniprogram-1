// 双人作业动态码（docs/03 §3.2 项6）：
// 主维保 → 生成并每 5 秒刷新（服务端按 5 秒步长轮换，60 秒内的码均可校验）；
// 配合人员 → 输入校验，通过后携码进入签到页（签到时服务端复核并一次性消费）。
const { issueDynamicCode, verifyDynamicCode, getOrderDetail } = require('../../services/order')
const { getUserInfo } = require('../../utils/auth')

const FALLBACK_STEP_SECONDS = 5
const TTL_SECONDS = 60

Page({
  data: {
    orderId: '',
    mode: '',            // ISSUE=主维保生成 / INPUT=配合人员输入
    isDual: false,       // 是否双人工单（有配合人员）
    code: '',
    inputCode: '',
    countdown: FALLBACK_STEP_SECONDS,
    ttlSeconds: TTL_SECONDS,
    expiresAt: '',
    submitting: false,
    errorText: ''
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
    if (query.mode === 'ISSUE' || query.mode === 'INPUT') {
      this.setData({ mode: query.mode, isDual: true })
      if (query.mode === 'ISSUE') this.startIssue()
    } else {
      this.resolveMode()
    }
  },

  onHide() {
    this.stopTimer()
  },

  onUnload() {
    this.stopTimer()
  },

  // 未显式指定模式：按当前登录人与工单主维保/配合人员比对自动判定
  async resolveMode() {
    try {
      const order = await getOrderDetail(this.data.orderId)
      const me = getUserInfo() || {}
      const isPrincipal = !!order.workerName &&
        (me.name === order.workerName ||
          (!!order.workerPlatformId && me.platformId === order.workerPlatformId))
      this.setData({ isDual: !!order.assistantName, mode: isPrincipal ? 'ISSUE' : 'INPUT' })
      if (isPrincipal) this.startIssue()
    } catch (e) {
      this.setData({ mode: 'INPUT' })
    }
  },

  switchMode(e) {
    const mode = e.currentTarget.dataset.mode
    if (mode === this.data.mode) return
    this.stopTimer()
    this.setData({ mode, errorText: '', code: '', inputCode: '' })
    if (mode === 'ISSUE') this.startIssue()
  },

  startIssue() {
    this.refresh()
    this.stopTimer()
    this.timer = setInterval(() => {
      const left = this.data.countdown - 1
      if (left <= 0) this.refresh()
      else this.setData({ countdown: left })
    }, 1000)
  },

  stopTimer() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },

  // 取当前步长的码：服务端每 5 秒轮换，60 秒内的码均可校验（避免配合人员输入途中换码即失败）
  async refresh() {
    try {
      const r = await issueDynamicCode(this.data.orderId)
      this.setData({
        code: r.code,
        countdown: r.stepSeconds || FALLBACK_STEP_SECONDS,
        ttlSeconds: r.ttlSeconds || TTL_SECONDS,
        expiresAt: r.expiresAt || '',
        errorText: ''
      })
    } catch (e) {
      this.stopTimer()
      this.setData({ errorText: (e && e.message) || '动态码生成失败，请重试' })
    }
  },

  onCodeInput(e) {
    this.setData({ inputCode: e.detail.value })
  },

  async onSubmit() {
    const code = String(this.data.inputCode || '').trim()
    if (!code) return wx.showToast({ title: '请输入动态码', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await verifyDynamicCode(this.data.orderId, { code: code })
      wx.showToast({ title: '校验通过', icon: 'success' })
      // 接回签到流程：以配合人员身份签到，动态码随签到提交由服务端复核并一次性消费
      wx.redirectTo({
        url: `/pages/order/checkin?orderId=${this.data.orderId}&role=ASSISTANT&dynamicCode=${code}`
      })
    } catch (e) {
      this.setData({ errorText: (e && e.message) || '动态码校验失败' })
      wx.showToast({ title: (e && e.message) || '动态码校验失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
