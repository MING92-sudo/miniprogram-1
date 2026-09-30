// 维保记录签名确认页（免登录）：安全管理员可远程打开（微信分享链接）签字，
// 或由维保人员把手机交给安全管理员本机代签
const unit = require('../../services/unit')

Page({
  data: {
    rid: '',
    token: '',
    view: null,
    confirmed: false,
    hasDrawn: false,
    submitting: false,
    loadError: ''
  },

  onLoad(query) {
    this.setData({ rid: query.rid || '', token: query.token || '' })
    this.fetchView()
  },

  onReady() {
    this.initCanvas()
  },

  fetchView() {
    const self = this
    unit.getSignView(this.data.rid, this.data.token)
      .then(function (view) {
        self.setData({ view: view, confirmed: !!view.confirmed, loading: false })
      })
      .catch(function (e) {
        self.setData({ loadError: e.message || '链接无效或已失效', loading: false })
      })
  },

  initCanvas() {
    const query = wx.createSelectorQuery()
    query.select('#sign-canvas').fields({ node: true, size: true }).exec((res) => {
      const canvas = res && res[0] && res[0].node
      if (!canvas) return
      const ctx = canvas.getContext('2d')
      const dpr = wx.getWindowInfo().pixelRatio || 2
      canvas.width = res[0].width * dpr
      canvas.height = res[0].height * dpr
      ctx.scale(dpr, dpr)
      ctx.lineWidth = 4
      ctx.lineCap = 'round'
      ctx.strokeStyle = '#000000'
      this.canvas = canvas
      this.ctx = ctx
    })
  },

  onTouchStart(e) {
    if (!this.ctx) return
    const t = e.touches[0]
    this.ctx.beginPath()
    this.ctx.moveTo(t.x, t.y)
  },

  onTouchMove(e) {
    if (!this.ctx) return
    const t = e.touches[0]
    this.ctx.lineTo(t.x, t.y)
    this.ctx.stroke()
    if (!this.data.hasDrawn) this.setData({ hasDrawn: true })
  },

  onClear() {
    if (!this.ctx) return
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height)
    this.setData({ hasDrawn: false })
  },

  async onSubmit() {
    if (this.data.submitting) return
    if (!this.data.hasDrawn) return wx.showToast({ title: '请在本页签名', icon: 'none' })
    this.setData({ submitting: true })
    const self = this
    wx.canvasToTempFilePath({
      canvas: this.canvas,
      success: (res) => {
        // 签名图按 mock 约定以路径回填（真实后端为 COS fileId）
        unit.confirmByToken(self.data.rid, self.data.token, {
          signatureUrl: res.tempFilePath
        })
          .then(function () {
            wx.showToast({ title: '已确认', icon: 'success' })
            self.fetchView()
          })
          .catch(function (e) {
            wx.showToast({ title: e.message || '确认失败', icon: 'none' })
          })
          .then(function () { self.setData({ submitting: false }) })
      },
      fail: () => {
        self.setData({ submitting: false })
        wx.showToast({ title: '签名导出失败', icon: 'none' })
      }
    })
  },

  // 分享给安全管理员远程签字（微信卡片打开本页）
  onShareAppMessage() {
    return {
      title: '维保记录确认 — ' + (this.data.view ? this.data.view.elevatorName : '电梯'),
      path: '/pages/unit/sign?rid=' + this.data.rid + '&token=' + this.data.token
    }
  }
})
