import http from './client'

/** 定位异常申述审核（docs/04 A.2/A.6）：列表 + 审核 */
export function approvals(params) {
  return http.get('/admin/approvals', { params })
}

export function auditApproval(id, approved, comment) {
  return http.post(`/admin/approvals/${id}/audit`, { approved, comment })
}
