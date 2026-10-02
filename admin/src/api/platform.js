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

/** 2.3 维保服务关系登记（multipart + contractFile，docs/07 实测口径；文件必填） */
export function registerService(form) {
  const fd = new FormData()
  Object.entries(form).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') fd.append(k, v)
  })
  return http.post('/platform/register/service', fd, { timeout: 60000 })
}

/** 2.4 维保人员登记（multipart + certificateFile） */
export function registerWorker(form) {
  const fd = new FormData()
  Object.entries(form).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') fd.append(k, v)
  })
  return http.post('/platform/register/worker', fd, { timeout: 60000 })
}
