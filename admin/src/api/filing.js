import http from './client'

/**
 * 外接建档单（W2，Task 14 管理端只读）：
 * DOC_COLLECTING → TAKEOVER_CHECKING → PENDING_CONFIRM → ARCHIVED
 */
export function listFilings(params) {
  return http.get('/filings', { params })
}

export function filingDetail(id) {
  return http.get(`/filings/${id}`)
}
