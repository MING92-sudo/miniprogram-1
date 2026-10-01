import http from './client'

/** 上报异常闭环：FAILED 清单 + 手动重报（仅逐条人工触发）+ PDF 导出 */
export function listRecords(params) {
  return http.get('/admin/records', { params })
}

export function reupload(id) {
  return http.post(`/platform/records/${id}/reupload`, {})
}

/** 维保记录 PDF（blob 直传，信封解包对其透明 */
export function exportPdf(id) {
  return http.get(`/admin/records/${id}/export-pdf`, { responseType: 'blob', timeout: 120000 })
}

/** 记录详情（复用使用单位记录契约，含照片/签字/隐患码/冻结报文） */
export function getRecord(id) {
  return http.get(`/unit/records/${id}`)
}
