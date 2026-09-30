// wx.request 统一封装
// 约定：统一响应 { code:0, message, data }；鉴权 Authorization: Bearer <JWT>；
// 写接口自动携带 X-Idempotency-Key；401 清登录态并跳转登录页。
const config = require('../config/index')
const { ERROR_CODES } = require('../constants/index')
const { getToken, clearAuth } = require('./auth')
const { uuid } = require('./util')

// 401：清登录态（Storage + globalData）并引导重新登录
function handleUnauthorized() {
  clearAuth()
  const app = typeof getApp === 'function' ? getApp() : null
  if (app && typeof app.logout === 'function') {
    app.logout() // 同步清 globalData（内部会再次 clearAuth，幂等无害）
  }
  const { redirectToLogin } = require('./guard')
  redirectToLogin()
}

function request(options) {
  // Mock 模式：前端独立开发，全部请求走本地数据（config.useMock 开关）
  if (config.useMock) {
    const { mockRequest } = require('../mock/server')
    return mockRequest(options)
  }

  const {
    url,
    method = 'GET',
    data,
    header = {},
    timeout,
    needAuth = true,
    idempotencyKey
  } = options

  return new Promise((resolve, reject) => {
    const h = Object.assign({ 'Content-Type': 'application/json' }, header)
    const token = getToken()
    if (needAuth && token) {
      h.Authorization = `Bearer ${token}`
    }

    const m = String(method).toUpperCase()
    if (['POST', 'PUT', 'PATCH'].indexOf(m) > -1 && !h['X-Idempotency-Key']) {
      h['X-Idempotency-Key'] = idempotencyKey || uuid()
    }

    wx.request({
      url: config.apiBaseUrl + url,
      method: m,
      data,
      header: h,
      timeout: timeout || config.requestTimeout,
      success(res) {
        const body = res.data || {}
        // 401：登录态失效
        if (res.statusCode === 401 || body.code === 401) {
          handleUnauthorized()
          reject({ code: 401, message: ERROR_CODES[401] })
          return
        }
        // 业务成功
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) {
          resolve(body.data)
          return
        }
        // 业务错误
        reject({
          code: body.code != null ? body.code : res.statusCode,
          message: body.message || '请求失败'
        })
      },
      fail(err) {
        reject({ code: -1, message: (err && err.errMsg) || '网络异常' })
      }
    })
  })
}

function get(url, data, options) {
  return request(Object.assign({ url, method: 'GET', data }, options))
}

function post(url, data, options) {
  return request(Object.assign({ url, method: 'POST', data }, options))
}

function put(url, data, options) {
  return request(Object.assign({ url, method: 'PUT', data }, options))
}

function del(url, data, options) {
  return request(Object.assign({ url, method: 'DELETE', data }, options))
}

module.exports = { request, get, post, put, del }
