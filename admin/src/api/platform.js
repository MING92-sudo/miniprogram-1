import http from './client'

/** 平台同步（docs/09 §1.2-3）：同步看板 + 手动触发（不提供批量自动重试，AGENTS §2.3） */
export function sync() {
  return http.post('/platform/sync', {})
}

export function syncStatus() {
  return http.get('/reg/sync-status')
}

export function uploadLogs(params) {
  return http.get('/reg/upload-logs', { params })
}
