import axios from 'axios'

// API 基址：开发默认走 Vite 代理（/api → localhost:8080）；生产同域反代（docs/09 §五）
export const API_BASE = import.meta.env.VITE_API_BASE || '/api'

/** 幂等键（AGENTS §3：所有写接口自动携带 X-Idempotency-Key） */
export function idempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'idem-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12)
}

/**
 * 统一信封解包（与小程序 utils/request.js 同口径）：{ code, message, data }。
 * code=0 → 返回 data；code!=0 → 抛出带 code/data 的错误
 * （401 登录过期；1002 手机号互斥 err.data.conflicts[]；2001 平台凭证未配置…）。
 * 非信封响应（如 PDF 二进制流）原样返回。
 */
export function resolveEnvelope(body) {
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) {
      return body.data
    }
    const err = new Error(body.message || `业务错误 ${body.code}`)
    err.code = body.code
    err.data = body.data || null
    throw err
  }
  return body
}

export function isAuthError(e) {
  return Boolean(e) && (e.code === 401 || e.response?.status === 401)
}

// 401 统一跳登录：由 auth store 注册，避免 api ↔ router 循环依赖
let unauthorizedHandler = null
export function onUnauthorized(fn) {
  unauthorizedHandler = fn
}

const http = axios.create({ baseURL: API_BASE, timeout: 15000 })

http.interceptors.request.use((cfg) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    cfg.headers.set('Authorization', `Bearer ${token}`)
  }
  const m = (cfg.method || '').toLowerCase()
  if ((m === 'post' || m === 'put' || m === 'patch') && !cfg.headers.get('X-Idempotency-Key')) {
    cfg.headers.set('X-Idempotency-Key', idempotencyKey())
  }
  return cfg
})

http.interceptors.response.use(
  (resp) => {
    try {
      return resolveEnvelope(resp.data)
    } catch (e) {
      if (isAuthError(e) && unauthorizedHandler) {
        unauthorizedHandler()
      }
      return Promise.reject(e)
    }
  },
  (error) => {
    const body = error.response && error.response.data
    if (body && typeof body === 'object' && 'code' in body) {
      try {
        return resolveEnvelope(body)
      } catch (e) {
        if (isAuthError(e) && unauthorizedHandler) {
          unauthorizedHandler()
        }
        return Promise.reject(e)
      }
    }
    const status = error.response ? error.response.status : 0
    const err = new Error(status === 0 ? '网络异常，请检查后端服务' : `请求失败 (${status})`)
    err.code = status || -1
    return Promise.reject(err)
  }
)

export default http
