// 统一请求封装：云托管走 wx.cloud.callContainer（免 request 合法域名），本地自建后端走 wx.request。
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

// 统一处理两种传输（callContainer / request）的响应：状态码与业务码口径一致
function handleResponse(res, resolve, reject) {
  const body = res.data || {}
  // 401：登录态失效
  if (res.statusCode === 401 || body.code === 401) {
    handleUnauthorized()
    reject({ code: 401, message: (body && body.message) || ERROR_CODES[401] })
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
}

// 云托管模式是否可用：config 开启 + 基础库支持 wx.cloud.callContainer
function canCallContainer() {
  return !!(config.cloud && config.cloud.enabled &&
    typeof wx !== 'undefined' && wx.cloud && typeof wx.cloud.callContainer === 'function')
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

    const onSuccess = (res) => handleResponse(res, resolve, reject)
    const onFail = (err) => reject({ code: -1, message: (err && err.errMsg) || '网络异常' })
    const timeoutMs = timeout || config.requestTimeout

    // 云托管：path 传接口路径（以 / 开头），服务名用 X-WX-SERVICE 头指定；
    // 走微信内网识别小程序身份，无需把域名配到 request 合法域名（docs/11）。
    if (canCallContainer()) {
      h['X-WX-SERVICE'] = config.cloud.service
      wx.cloud.callContainer({
        config: { env: config.cloud.env },
        path: url,
        method: m,
        data,
        header: h,
        timeout: timeoutMs,
        success: onSuccess,
        fail: onFail
      })
      return
    }

    // 本地/自建后端：普通 https（或开发者工具关闭域名校验的 localhost）
    wx.request({
      url: config.apiBaseUrl + url,
      method: m,
      data,
      header: h,
      timeout: timeoutMs,
      success: onSuccess,
      fail: onFail
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
