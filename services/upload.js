// 文件上传（水印照片、签名图）
// 架构约定：腾讯云 COS + STS 临时凭证直传；token 类凭证由后端签发，小程序不接触长期密钥
const config = require('../config/index')
const { getToken } = require('../utils/auth')

// 上传单张图片，返回可访问 fileId/url
function uploadImage(filePath, formData) {
  // Mock 模式：不真实上传，本地路径可直接用于预览
  if (config.useMock) {
    return Promise.resolve({ fileId: 'mock_file_' + Date.now(), url: filePath })
  }
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: config.apiBaseUrl + '/files/upload',
      filePath,
      name: 'file',
      formData: formData || {},
      header: {
        Authorization: `Bearer ${getToken()}`
      },
      timeout: config.uploadTimeout,
      success(res) {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 0) {
            resolve(body.data)
          } else {
            reject({ code: body.code, message: body.message || '上传失败' })
          }
        } catch (e) {
          reject({ code: -1, message: '上传响应解析失败' })
        }
      },
      fail(err) {
        reject({ code: -1, message: (err && err.errMsg) || '上传失败' })
      }
    })
  })
}

module.exports = {
  uploadImage
}
