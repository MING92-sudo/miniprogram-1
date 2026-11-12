import http from './client'

/**
 * 移交单（W1，Task 14 管理端只读）：列表 + 详情 + 经理确认归档。
 * 状态：DOC_SUBMITTING → INSPECTING →(RECTIFYING⇄)→ PENDING_CONFIRM → ARCHIVED
 */
export function listHandovers(params) {
  return http.get('/handovers', { params })
}

export function handoverDetail(id) {
  return http.get(`/handovers/${id}`)
}

/** 确认归档：确认人取当前登录账号（管理端只做兜底，正常由维保经理在小程序确认） */
export function confirmHandover(id, body) {
  return http.post(`/handovers/${id}/confirm`, body)
}
