import http from './client'

/** 预警：规则配置 / 记录 / 生成 */
export function alertRules() {
  return http.get('/admin/alert-rules')
}

export function updateAlertRule(id, body) {
  return http.put(`/admin/alert-rules/${id}`, body)
}

export function alerts(params) {
  return http.get('/admin/alerts', { params })
}

export function generateAlerts() {
  return http.post('/admin/alerts/generate', {})
}
