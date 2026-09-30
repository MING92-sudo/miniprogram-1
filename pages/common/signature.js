// 签名板：canvas 手写签名 → 导出图片回传上一页
Page({
  data: {
    orderId: '',
    from: '',
    hasDrawn: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '', from: query.from || '' })
    // 在被打开页自身捕获 EventChannel（规范用法；拖到 onConfirm 时再取可能为 undefined）
    try {
      this._channel = this.getOpenerEventChannel ? this.getOpenerEventChannel() : null
    } catch (e) {
      this._channel = null
    }
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
        const data = { path: res.tempFilePath }
        // 通道①：EventChannel（规范用法，在被打开页自身 emit）
        if (this._channel && typeof this._channel.emit === 'function') {
          try {
            this._channel.emit('signatureDone', data)
          } catch (e) {
            // EventChannel 失效时走通道②
          }
        }
        // 通道②：直接调用上一页方法（未注册 events 监听 / EventChannel 失效时兜底；
        // 两通道同时生效时 setData 幂等，无副作用）
        {
          const pages = getCurrentPages()
          const prev = pages[pages.length - 2]
          if (prev && typeof prev.onSignatureReady === 'function') {
            prev.onSignatureReady(data)
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
