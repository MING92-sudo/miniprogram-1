// 水印相机：拍照 → canvas 合成水印（时间/工单号/定位标识）→ 压缩导出回传
// 约定：压缩后长边 ≤1200px、≤500KB（docs/04），压缩实现见 utils/image
const { formatTime } = require('../../utils/util')
const { compressImage } = require('../../utils/image')

Page({
  data: {
    orderId: '',
    itemId: '',
    itemName: '', // 检查项名称水印（检查项拍照时携带）
    from: '',
    photo: '', // 原始照片
    output: '', // 水印合成结果
    watermarkTime: '',
    locationText: '', // 定位坐标水印
    ready: false,
    processing: false
  },

  onLoad(query) {
    // 具体位置文字（逆地址解析结果，优先）；无地址时回退坐标
    // 部分基础库/真机不会自动解码 query（上一页用 encodeURIComponent 编码了中文），
    // 这里手动解码一次；已解码的字符串不含 % 序列，重复解码无副作用
    let locationText = ''
    try {
      locationText = decodeURIComponent(query.address || '')
    } catch (e) {
      locationText = query.address || ''
    }
    if (!locationText) {
      const lat = parseFloat(query.lat)
      const lng = parseFloat(query.lng)
      if (!isNaN(lat) && !isNaN(lng)) {
        locationText = lat.toFixed(5) + ', ' + lng.toFixed(5)
      }
    }
    let itemName = ''
    try {
      itemName = decodeURIComponent(query.itemName || '')
    } catch (e) {
      itemName = query.itemName || ''
    }
    this.setData({
      orderId: query.orderId || '',
      itemId: query.itemId || '',
      itemName: itemName,
      from: query.from || '',
      watermarkTime: formatTime(),
      locationText
    })
  },

  takePhoto() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['camera'],
      sizeType: ['compressed'],
      camera: 'front',
      success: (res) => {
        this.setData({ photo: res.tempFiles[0].tempFilePath, ready: false })
        this.renderWatermark(res.tempFiles[0].tempFilePath)
      }
    })
  },

  // 将照片绘制到 canvas 并叠加水印文字
  renderWatermark(src) {
    this.setData({ processing: true })
    // 先取照片真实宽高：部分真机 canvas.createImage() 的 width/height 偶发为 0，
    // 导致 drawImage 按 0 尺寸绘制 → 画布只剩黑色底，导出黑图
    wx.getImageInfo({
      src,
      success: (info) => this._drawWatermark(src, info.width, info.height),
      fail: () => this._drawWatermark(src, 0, 0)
    })
  },

  _drawWatermark(src, iw, ih) {
    wx.createSelectorQuery()
      .select('#wm-canvas')
      .fields({ node: true, size: true })
      .exec((res) => {
        const canvas = res && res[0] && res[0].node
        if (!canvas) {
          this.setData({ processing: false })
          return wx.showToast({ title: '画布初始化失败', icon: 'none' })
        }
        const sys = wx.getWindowInfo()
        const dpr = sys.pixelRatio || 2
        // 画布不可见时 size 可能为 0，兜底用窗口宽 + 600rpx 换算
        const cssW = res[0].width || sys.windowWidth
        const cssH = res[0].height || Math.round((600 / 750) * sys.windowWidth)
        canvas.width = Math.round(cssW * dpr)
        canvas.height = Math.round(cssH * dpr)
        const ctx = canvas.getContext('2d')
        ctx.scale(dpr, dpr)

        const img = canvas.createImage()
        img.onload = () => {
          // cover 裁剪：按画布尺寸铺满，宽高不足的一侧被裁掉
          const w = iw || img.width || cssW
          const h = ih || img.height || cssH
          const scale = Math.max(cssW / w, cssH / h)
          const dw = w * scale
          const dh = h * scale
          ctx.fillStyle = '#000000'
          ctx.fillRect(0, 0, cssW, cssH)
          ctx.drawImage(img, (cssW - dw) / 2, (cssH - dh) / 2, dw, dh)

          // 底部水印条：行数随内容动态（时间/工单/检查项/位置）
          const rows = ['时间：' + this.data.watermarkTime, '工单：' + (this.data.orderId || '-')]
          if (this.data.itemName) rows.push('项目：' + this.data.itemName)
          if (this.data.locationText) rows.push('位置：' + this.data.locationText)
          const barH = 20 + rows.length * 24
          ctx.fillStyle = 'rgba(0, 0, 0, 0.6)'
          ctx.fillRect(0, cssH - barH, cssW, barH)
          ctx.fillStyle = '#ffffff'
          ctx.font = '13px sans-serif'
          ctx.textBaseline = 'top'
          // 超宽文字按画布宽度截断，避免溢出画面
          const maxW = cssW - 24
          rows.forEach(function (line, i) {
            let text = line
            while (ctx.measureText(text).width > maxW && text.length > 4) {
              text = text.slice(0, text.length - 2) + '…'
            }
            ctx.fillText(text, 12, cssH - barH + 10 + i * 24)
          })

          wx.canvasToTempFilePath({
            canvas,
            fileType: 'jpg',
            quality: 0.8,
            success: (r) => {
              // 导出后压缩至约定范围（长边 ≤1200px、≤500KB）
              compressImage(r.tempFilePath).then((path) => {
                this.setData({ output: path, ready: true, processing: false })
              })
            },
            fail: () => {
              this.setData({ processing: false })
              wx.showToast({ title: '水印合成失败', icon: 'none' })
            }
          })
        }
        img.onerror = () => {
          this.setData({ processing: false })
          wx.showToast({ title: '照片加载失败', icon: 'none' })
        }
        img.src = src
      })
  },

  // 确认使用：回传上一页
  onConfirm() {
    if (!this.data.ready) return wx.showToast({ title: '请先拍照', icon: 'none' })
    const pages = getCurrentPages()
    const prev = pages[pages.length - 2]
    if (prev && typeof prev.onPhotoReady === 'function') {
      prev.onPhotoReady(this.data.output)
    }
    wx.navigateBack()
  },

  onRetake() {
    this.setData({ photo: '', output: '', ready: false })
    this.takePhoto()
  }
})
