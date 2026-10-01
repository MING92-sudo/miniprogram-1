// 图片压缩（长边 ≤1200px、文件 ≤500KB，见 constants/IMAGE_LIMIT）
// 任何一步失败均回退原图，不阻断业务流程
const { IMAGE_LIMIT } = require('../constants/index')

const QUALITY_STEPS = [80, 60, 40, 20]

function getImageInfo(src) {
  return new Promise((resolve, reject) => {
    wx.getImageInfo({ src, success: resolve, fail: reject })
  })
}

// 本地文件大小（字节）；失败返回 0
function getFileSize(path) {
  return new Promise((resolve) => {
    wx.getFileSystemManager().getFileInfo({
      filePath: path,
      success: (res) => resolve(res.size || 0),
      fail: () => resolve(0)
    })
  })
}

// wx.compressImage：quality 0-100；compressedWidth/Height 需基础库 2.25.0+，低版本自动忽略
function compressOnce(src, quality, width, height) {
  return new Promise((resolve, reject) => {
    const opts = { src, quality, success: resolve, fail: reject }
    if (width && height) {
      opts.compressedWidth = width
      opts.compressedHeight = height
    }
    wx.compressImage(opts)
  })
}

// 压缩单张照片至约定范围，返回本地临时路径
async function compressImage(src) {
  if (!src) return src

  // 1) 尺寸：长边超限时计算等比缩放目标尺寸
  let targetW = 0
  let targetH = 0
  try {
    const info = await getImageInfo(src)
    const longEdge = Math.max(info.width || 0, info.height || 0)
    if (longEdge > IMAGE_LIMIT.MAX_EDGE) {
      const scale = IMAGE_LIMIT.MAX_EDGE / longEdge
      targetW = Math.round((info.width || 0) * scale)
      targetH = Math.round((info.height || 0) * scale)
    }
  } catch (e) {
    // 尺寸获取失败时仅压质量
  }

  // 2) 原图已达标且无需缩放时直接返回
  const origSize = await getFileSize(src)
  if (!targetW && origSize && origSize <= IMAGE_LIMIT.MAX_SIZE) return src

  // 3) 逐级降质量重压，直至体积达标或质量阶梯用尽（尽力而为）
  let current = src
  for (let i = 0; i < QUALITY_STEPS.length; i++) {
    let res
    try {
      res = await compressOnce(src, QUALITY_STEPS[i], targetW, targetH)
    } catch (e) {
      // 环境不支持 compressImage 或本次压缩失败：保留已压结果
      return current
    }
    if (res && res.tempFilePath) current = res.tempFilePath
    const size = await getFileSize(current)
    if (!size || size <= IMAGE_LIMIT.MAX_SIZE) return current
  }
  return current
}

module.exports = { compressImage }
