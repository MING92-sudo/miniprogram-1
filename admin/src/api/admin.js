import http from './client'

/** 管理端聚合：GET /admin/dashboard、GET /admin/records、GET /admin/stats、GET /admin/elevators */
export function dashboard() {
  return http.get('/admin/dashboard')
}

export function records(params) {
  return http.get('/admin/records', { params })
}

export function stats(days) {
  return http.get('/admin/stats', { params: days ? { days } : {} })
}

export function elevatorsAdmin() {
  return http.get('/admin/elevators')
}
