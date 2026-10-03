import http from './client'

/** 检查项模板管理（docs/04 A.4.2）：OFFICIAL 只读，CUSTOM 增改/启停 */
export function list(params) {
  return http.get('/admin/templates', { params })
}

export function create(body) {
  return http.post('/admin/templates', body)
}

export function update(id, body) {
  return http.put(`/admin/templates/${id}`, body)
}

export function setEnabled(id, enabled) {
  return http.put(`/admin/templates/${id}/enabled`, { enabled })
}
