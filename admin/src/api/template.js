import http from './client'

/** 检查项模板管理（docs/04 A.4.2，docs/09 二期）：OFFICIAL 257 行只读 + CUSTOM 自定义模板 CRUD/启停 */
export function listTemplates(params) {
  return http.get('/admin/templates', { params })
}

export function createTemplate(body) {
  return http.post('/admin/templates', body)
}

export function updateTemplate(id, body) {
  return http.put(`/admin/templates/${id}`, body)
}

export function setTemplateEnabled(id, enabled) {
  return http.put(`/admin/templates/${id}/enabled`, { enabled })
}
