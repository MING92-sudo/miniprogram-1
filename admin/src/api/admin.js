import http from './client'

/** 管理端聚合（docs/09 §4.1）：GET /admin/dashboard、GET /admin/records、GET /admin/stats、GET /admin/elevators */
export function dashboard() {
  return http.get('/admin/dashboard')
}

export function records(params) {
  return http.get('/admin/records', { params })
}

