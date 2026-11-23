import http from './client'

/** 上报异常闭环：FAILED 清单 + 手动重报（仅逐条人工触发，AGENTS §2.3）+ PDF 导出 */
export function listRecords(params) {
  return http.get('/admin/records', { params })
}

/** 按后端 total 逐页拉取全部记录，直到累计行数达到 total（不使用固定大 size 兜底）。 */
export async function listAllRecords(params = {}) {
  const size = params.size || 20
  const all = []
  let total = 0
  let page = 1
  let list = []
  do {
    const data = await listRecords({ ...params, page, size })
    list = (data && data.list) || []
    total = (data && data.total) || all.length + list.length
    all.push(...list)
    page += 1
  } while (list.length > 0 && all.length < total)
  return { list: all, total: Math.max(total, all.length), page: 1, size }
}

export function reupload(id) {
  return http.post(`/platform/records/${id}/reupload`, {})
}

export function exportPdf(id) {
  return http.get(`/admin/records/${id}/export-pdf`, { responseType: 'blob', timeout: 120000 })
}

/** 急修单 PDF（GET /admin/faults/{id}/export-pdf）；blob 直传 */
export function exportFaultPdf(id) {
  return http.get(`/admin/faults/${id}/export-pdf`, { responseType: 'blob', timeout: 120000 })
}

/** 记录详情（复用使用单位记录契约，含照片/签字/隐患码/冻结报文） */
export function getRecord(id) {
  return http.get(`/unit/records/${id}`)
}
