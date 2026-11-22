// 文件上传（水印照片、签名图）架构约定：腾讯云 COS + STS 临时凭证直传；
const config = require('../config/index')
const { getToken } = require('../utils/auth')

// 上传单张图片，返回可访问 fileId/urltoken 可选：传入门户令牌（use_unit 账号）时用它，否则取员工令牌（默认）。
function uploadImage(filePath, formData, token) {
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
        Authorization: `Bearer ${token || getToken()}`
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

// 免登录分享链签字页签名图上传：token 即凭证（后端 shareToken + 可签状态校验先于写盘），不带 Authorization——/files/upload 必须登录，匿名 token 链无会话可用（生产 401）。
function uploadSignatureByToken(recordId, token, filePath) {
  if (config.useMock) {
    return Promise.resolve({ fileId: 'mock_file_' + Date.now(), url: filePath })
  }
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: config.apiBaseUrl + '/unit/records/' + recordId + '/signature-upload?token=' + encodeURIComponent(token || ''),
      filePath,
      name: 'file',
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
  uploadImage,
  uploadSignatureByToken
}
