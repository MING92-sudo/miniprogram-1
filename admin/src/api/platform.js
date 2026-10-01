import http from './client'

/** 平台同步：同步看板 + 手动触发（不提供批量自动重试） */
export function sync() {
  return http.post('/platform/sync', {})
}

export function syncStatus() {
  return http.get('/reg/sync-status')
}

export function uploadLogs(params) {
  return http.get('/reg/upload-logs', { params })
}
