// 签名板：canvas 手写签名 → 导出图片回传上一页
Page({
  data: {
    orderId: '',
    from: '',
    hasDrawn: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '', from: query.from || '' })
  },

  onReady() {
    // 首次渲染完成后画布才有尺寸；onLoad 阶段 select 会拿到 null / 0×0
    this.initCanvas()
  },

  initCanvas() {
    const query = wx.createSelectorQuery()
    query
      .select('#sig-canvas')
      .fields({ node: true, size: true })
      .exec((res) => {
        const canvas = res && res[0] && res[0].node
        if (!canvas) {
          wx.showToast({ title: '签名画布初始化失败', icon: 'none' })
          return
        }
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
    const t = e.touches[0]
    this.ctx.beginPath()
    this.ctx.moveTo(t.x, t.y)
  },

  onTouchMove(e) {
    const t = e.touches[0]
    this.ctx.lineTo(t.x, t.y)
    this.ctx.stroke()
    if (!this.data.hasDrawn) this.setData({ hasDrawn: true })
  },

  onClear() {
    const w = this.canvas.width
    const h = this.canvas.height
    this.ctx.clearRect(0, 0, w, h)
    this.setData({ hasDrawn: false })
  },

  async onConfirm() {
    if (!this.data.hasDrawn) return wx.showToast({ title: '请先签名', icon: 'none' })
    wx.canvasToTempFilePath({
      canvas: this.canvas,
      success: (res) => {
        const pages = getCurrentPages()
        const prev = pages[pages.length - 2]
        // 通过 EventChannel 通知上一页（checkout 等待 signatureDone）
        if (prev && prev.getOpenerEventChannel) {
          try {
            prev.getOpenerEventChannel().emit('signatureDone', { path: res.tempFilePath })
          } catch (e) {
            // 降级：直接回退
          }
        }
        wx.navigateBack()
      },
      fail: () => {
        wx.showToast({ title: '签名导出失败', icon: 'none' })
      }
    })
  }
})
