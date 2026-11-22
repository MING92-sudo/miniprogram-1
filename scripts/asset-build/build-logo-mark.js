const Jimp = require('jimp')
const path = require('path')
const ROOT = path.join(__dirname, '..', '..')

function isWhite(r, g, b) { return r > 232 && g > 232 && b > 232 }

;(async () => {
  const img = await Jimp.read(path.join(ROOT, 'assets/brand/logo.jpg'))
  const W = img.bitmap.width, H = img.bitmap.height
  // 圆形徽标位于上部约 0–78% 高度；BOWEI 字样在底部 >80%，先排除底部
  const topH = Math.floor(H * 0.78)
  // 在上部区域找非白像素 bbox（圆形徽标）
  let minX = W, minY = topH, maxX = 0, maxY = 0
  img.scan(0, 0, W, topH, function (x, y, idx) {
    const d = this.bitmap.data
    if (!isWhite(d[idx], d[idx + 1], d[idx + 2])) {
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  })
  const pad = 4
  minX = Math.max(0, minX - pad); minY = Math.max(0, minY - pad)
  maxX = Math.min(W - 1, maxX + pad); maxY = Math.min(topH - 1, maxY + pad)
  const cw = maxX - minX + 1, ch = maxY - minY + 1
  img.crop(minX, minY, cw, ch)
  // 近白像素转透明
  img.scan(0, 0, cw, ch, function (x, y, idx) {
    const d = this.bitmap.data
    if (isWhite(d[idx], d[idx + 1], d[idx + 2])) d[idx + 3] = 0
  })
  // 归一化为正方形画布（居中），输出 240×240
  const size = Math.max(cw, ch)
  const canvas = new Jimp(size, size, 0x00000000)
  canvas.composite(img, Math.floor((size - cw) / 2), Math.floor((size - ch) / 2))
  canvas.resize(240, 240)
  const out = path.join(ROOT, 'assets/brand/logo-mark.png')
  await canvas.writeAsync(out)
  console.log(JSON.stringify({ src: W + 'x' + H, cropBBox: [minX, minY, maxX, maxY], crop: cw + 'x' + ch, out: 'logo-mark.png 240x240 transparent' }))
})().catch(e => { console.error('ERR', e.message); process.exit(1) })