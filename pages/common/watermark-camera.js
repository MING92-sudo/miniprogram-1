// 水印相机：取证令牌 → 拍照 → canvas 合成水印 → 压缩导出回传
// 约定：压缩后长边 ≤1200px、≤500KB（docs/04），压缩实现见 utils/image
//
//水印文本一律从服务端签发的取证令牌载荷渲染，**不接受任何 URL 参数传入的坐标/时间/地址**。
// 早期版本从 query 读取 lat/lng/address/watermarkTime，等于让调用方自行决定证据内容，
// 可用构造 URL 伪造水印（docs/12 审查 NB3）。现改为令牌的纯函数：
// 工单=oid、项目=iid、时间=st（服务端签发时刻）、位置=lat/lng（已通过地理围栏校验）。
// 说明：小程序端无法验证 HMAC，故伪造令牌只能渲染出假水印，但服务端在提交时会验签拒绝，
// 该照片无法进入正式维保记录——权威值始终以服务端记录为准。
const { formatTime } = require('../../utils/util')
const { compressImage } = require('../../utils/image')

// URL-safe base64 载荷解码（签名部分不校验，校验由服务端在提交时完成）
function decodeEvidence(token) {
  if (!token) return null
  const parts = String(token).split('.')
  if (parts.length !== 2 || !parts[1]) return null
  let b64 = parts[0].replace(/-/g, '+').replace(/_/g, '/')
  while (b64.length % 4) b64 += '='
  try {
    const bytes = wx.base64ToArraySync(b64)
    let raw = ''
    for (let i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i])
    const json = decodeURIComponent(
      raw
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    const payload = JSON.parse(json)
    return payload && payload.oid && payload.st ? payload : null
  } catch (e) {
    return null
  }
}

Page({
  data: {
    orderId: '',
    itemId: '',
    evidence: null, // 服务端签发的取证令牌载荷（权威水印来源）
    evidenceToken: '', // 原令牌，提交时随照片回传由服务端验签
    evidenceMissing: false,
    from: '',
    lens: 'back', // 摄像头朝向：front=签到自拍 / back=现场照
    photo: '', // 原始照片
    output: '', // 水印合成结果
    watermarkTime: '',
    locationText: '', // 定位坐标水印
    ready: false,
    processing: false
  },

  onLoad(query) {
    const evidence = decodeEvidence(query.evidence)
    if (!evidence) {
      // fail closed：无服务端令牌不拍——否则水印内容由调用方决定，等于无取证价值
      this.setData({ evidenceMissing: true, ready: false, processing: false })
      return
    }
    this.setData({
      from: query.from || '',
      evidence,
      evidenceToken: String(query.evidence || ''),
      orderId: evidence.oid || '',
      itemId: evidence.iid || '',
      watermarkTime: formatTime(new Date(Number(evidence.st) * 1000)),
      locationText: Number(evidence.lat).toFixed(5) + ', ' + Number(evidence.lng).toFixed(5),
      // 摄像头朝向按场景：签到自拍=前置；检查项现场照=后置
      lens: query.from === 'checkin' ? 'front' : 'back',
      ready: false
    })
  },

  onReady() {
    // 必须等渲染完成（onReady）再拉起相机：onLoad 阶段部分安卓机不弹相机，
    // 且此时 #wm-canvas 未布局，拍完立即合成会报"画布初始化失败"
    if (this.data.evidenceMissing) {
      wx.showToast({ title: '缺少取证令牌，请返回重试', icon: 'none' })
      return
    }
    this.takePhoto()
  },

  takePhoto() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['camera'],
      sizeType: ['compressed'],
      camera: this.data.lens || 'back',
      success: (res) => {
        this.setData({ photo: res.tempFiles[0].tempFilePath, ready: false })
        this.renderWatermark(res.tempFiles[0].tempFilePath)
      },
      fail: (err) => {
        const msg = (err && err.errMsg) || ''
        if (msg.indexOf('cancel') > -1) {
          // 用户主动取消：留在本页，可点"拍照"重试
          return wx.showToast({ title: '已取消拍摄', icon: 'none' })
        }
        // 权限拒绝等异常：引导去设置开启，或返回上一页
        wx.showModal({
          title: '无法打开相机',
          content: '请检查相机权限是否已开启。',
          confirmText: '去设置',
          cancelText: '返回',
          success: (r) => {
            if (r.confirm) return wx.openSetting({})
            wx.navigateBack()
          }
        })
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

          // 底部水印条：全部取自令牌载荷（权威值），行数随内容动态
          const rows = ['时间：' + this.data.watermarkTime, '工单：' + (this.data.orderId || '-')]
          if (this.data.itemId) rows.push('项目：' + this.data.itemId)
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

  // 确认使用：回传合成图与取证令牌载荷（调用方随照片一起提交，由服务端验签）
  onConfirm() {
    if (!this.data.ready) return wx.showToast({ title: '请先拍照', icon: 'none' })
    const pages = getCurrentPages()
    const prev = pages[pages.length - 2]
    if (prev && typeof prev.onPhotoReady === 'function') {
      prev.onPhotoReady(this.data.output, this.data.evidenceToken)
    }
    wx.navigateBack()
  },

  onRetake() {
    this.setData({ photo: '', output: '', ready: false })
    this.takePhoto()
  }
})
