// 签署确认（M5，屏 11）：一次性任务 taskId+token（免登录）；核对项目/异常整改/照片 → 勾选确认门控（§8.4 第二档）
// → 手写签名 → 时间戳/GPS（后端写入）→ 锁定；拒签必填原因。token 重放/转发由后端一次性校验拦截。
const unit = require('../../../services/unit')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-client/pages/sign/index'], {
  data: {
    rid: '', token: '', view: null, confirmed: false, refused: false,
    hasDrawn: false, consent: false, submitting: false, loadError: ''
  },
  onLoad(query) {
    this.setData({ rid: (query && (query.taskId || query.rid)) || '', token: (query && query.token) || '' })
    this.fetchView()
  },
  onReady() { this.initCanvas() },
  onBack() { nav.back() },
  fetchView() {
    unit.getSignView(this.data.rid, this.data.token)
      .then((view) => this.setData({ view, confirmed: !!view.confirmed, refused: view.confirmStatus === 'REFUSED', loadError: '' }))
      .catch((e) => this.setData({ loadError: (e && e.message) || '链接无效或已失效' }))
  },
  initCanvas() {
    wx.createSelectorQuery().select('#sign-canvas').fields({ node: true, size: true }).exec((res) => {
      const canvas = res && res[0] && res[0].node
      if (!canvas) return
      const ctx = canvas.getContext('2d')
      const dpr = wx.getWindowInfo().pixelRatio || 2
      canvas.width = res[0].width * dpr; canvas.height = res[0].height * dpr
      ctx.scale(dpr, dpr); ctx.lineWidth = 4; ctx.lineCap = 'round'; ctx.strokeStyle = '#000000'
      this.canvas = canvas; this.ctx = ctx
    })
  },
  onTouchStart(e) { if (!this.ctx) return; const t = e.touches[0]; this.ctx.beginPath(); this.ctx.moveTo(t.x, t.y) },
  onTouchMove(e) { if (!this.ctx) return; const t = e.touches[0]; this.ctx.lineTo(t.x, t.y); this.ctx.stroke(); if (!this.data.hasDrawn) this.setData({ hasDrawn: true }) },
  onClear() { if (!this.ctx) return; this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height); this.setData({ hasDrawn: false }) },
  toggleConsent() { this.setData({ consent: !this.data.consent }) },
  // 勾选门控（第二档）：未勾选拦截 + 原因；已勾选未签名拦截
  onSubmit() {
    if (this.data.submitting) return
    if (!this.data.consent) return wx.showToast({ title: '请先勾选「我已核对…」再签署', icon: 'none' })
    if (!this.data.hasDrawn) return wx.showToast({ title: '请在签字区手写签名', icon: 'none' })
    this.setData({ submitting: true })
    wx.canvasToTempFilePath({
      canvas: this.canvas,
      success: (res) => {
        unit.confirmByToken(this.data.rid, this.data.token, { signatureUrl: res.tempFilePath })
          .then(() => { wx.showToast({ title: '已签署', icon: 'success' }); this.setData({ submitting: false }); this.fetchView() })
          .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '签署失败', icon: 'none' }) })
      },
      fail: () => { this.setData({ submitting: false }); wx.showToast({ title: '签名导出失败', icon: 'none' }) }
    })
  },
  onRefuse() {
    wx.showModal({
      title: '拒绝签署', editable: true, placeholderText: '请填写拒绝原因（必填）',
      success: (res) => {
        if (!res.confirm) return
        const reason = (res.content || '').trim()
        if (!reason) return wx.showToast({ title: '拒绝原因必填', icon: 'none' })
        unit.refuseRecordByToken(this.data.rid, this.data.token, reason)
          .then(() => { wx.showToast({ title: '已拒签退回', icon: 'success' }); this.fetchView() })
          .catch((e) => wx.showToast({ title: (e && e.message) || '拒签失败', icon: 'none' }))
      }
    })
  },
  onShareAppMessage() {
    return { title: '维保记录确认 — ' + (this.data.view ? this.data.view.elevatorName : '电梯'), path: '/pkg-client/pages/sign/index?taskId=' + this.data.rid + '&token=' + this.data.token }
  }
})