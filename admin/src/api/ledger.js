import http from './client'

/** 合规台账（docs/09 §1.2-5）：复用小程序现有路由，管理端只读展示 */
export function inspects() {
  return http.get('/inspects')
}

export function drills() {
  return http.get('/drills')
}

export function rescues(params) {
  return http.get('/rescues', { params })
}

export function faults(params) {
  return http.get('/faults', { params })
}
