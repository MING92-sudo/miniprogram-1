import http from './client'

/** 操作审计日志（SYS_ADMIN 只读） */
export function opLogs(params) {
  return http.get('/admin/op-logs', { params })
}
