import http from './client'

/** 平台同步（docs/09 §1.2-3）：同步看板 + 手动触发（不提供批量自动重试，AGENTS §2.3） */
export function sync() {
  return http.post('/platform/sync', {})
}

/** 2.5 单位维保人员查询（平台原始列表，只读） */
export function platformWorkers(changState) {
  return http.get('/platform/workers', { params: { changState: changState || 0 }, timeout: 60000 })
}

/** 2.7 单梯查询（新建/编辑电梯时按 设备代码/出厂编号/注册代码 回填平台字段；平台不支持按电梯编号查询） */
export function queryElevator(cond) {
  return http.post('/platform/query/elevator', cond, { timeout: 60000 })
}

export function uploadLogs(params) {
  return http.get('/reg/upload-logs', { params })
}

/** 存量维保人员 platform_id 回填（docs/04 B.5）：按证书号查 2.5 补齐已建档但缺 ID 的人员 */
export function backfillWorkers() {
  return http.post('/platform/workers/backfill', {}, { timeout: 60000 })
}

export function registerWorker(form) {
  const fd = new FormData()
  Object.entries(form).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') fd.append(k, v)
  })
  return http.post('/platform/register/worker', fd, { timeout: 60000 })
}
