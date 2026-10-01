import http from './client'

/** 操作审计日志（SYS_ADMIN 只读，docs/02 op_log） */
export function opLogs(params) {
  return http.get('/admin/op-logs', { params })
}
