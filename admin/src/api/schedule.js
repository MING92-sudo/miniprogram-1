import http from './client'

/** 计划调度与延期审批（docs/04 A.5，docs/09 二期）：生成/列表/指派/延期/审批/转派/建议/冲突 */
export function listPlans(params) {
  return http.get('/admin/plans', { params })
}

export function generatePlans(body) {
  return http.post('/admin/plans/generate', body)
}

export function assignPlan(id, body) {
  return http.put(`/admin/plans/${id}/assign`, body)
}

export function applyDelay(id, body) {
  return http.post(`/admin/plans/${id}/delay`, body)
}

export function listDelays(status) {
  return http.get('/admin/plans/delays', { params: status ? { status } : {} })
}

export function decideDelay(id, approved, comment) {
  return http.put(`/admin/plans/delays/${id}/decide`, { approved, comment })
}

export function planConflicts(planId) {
  return http.get('/admin/plans/conflicts', { params: { planId } })
}

export function planSuggestion(planId) {
  return http.get('/admin/plans/schedule-suggestion', { params: { planId } })
}

export function transferOrder(id, body) {
  return http.post(`/admin/orders/${id}/transfer`, body)
}

export function employees() {
  return http.get('/employees')
}
