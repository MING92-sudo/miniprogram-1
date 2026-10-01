// 维保记录签名确认页（免登录）：安全管理员可远程打开（微信分享链接）签字，
// 或由维保人员把手机交给安全管理员本机代签
const unit = require('../../services/unit')
const { uploadImage } = require('../../services/upload')

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
    try {
      const tempPath = await new Promise((resolve, reject) => {
        wx.canvasToTempFilePath({
          canvas: this.canvas,
          success: (res) => resolve(res.tempFilePath),
          fail: () => reject(new Error('签名导出失败'))
        })
      })
      // 签名图先上传换 fileId，再提交 fileId；签名图 URL 由服务端按 fileId 反查。
      // 此前直接提交本地临时路径，导致合规记录里存的是设备路径、PDF 导出取不到图，
      // 且任意 URL 字符串即可把记录置为"已确认签字"。
      const uploaded = await uploadImage(tempPath)
      await unit.confirmByToken(this.data.rid, this.data.token, {
        signatureFileId: uploaded.fileId
      })
      wx.showToast({ title: '已确认', icon: 'success' })
      this.fetchView()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '确认失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  // 分享给安全管理员远程签字（微信卡片打开本页）
  onShareAppMessage() {
    return {
      title: '维保记录确认 — ' + (this.data.view ? this.data.view.elevatorName : '电梯'),
      path: '/pages/unit/sign?rid=' + this.data.rid + '&token=' + this.data.token
    }
  }
})
