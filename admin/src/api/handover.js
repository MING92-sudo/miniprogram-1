import http from './client'

/**
 * 移交单（W1，Task 14 管理端只读）：列表 + 详情 + 经理确认归档。
 * 状态：DOC_SUBMITTING → INSPECTING →(RECTIFYING⇄)→ PENDING_CONFIRM → ARCHIVED
 */
export function listHandovers(params) {
  return http.get('/handovers', { params })
}

export function handoverDetail(id) {
  return http.get(`/handovers/${id}`)
}

/** 确认归档：确认人取当前登录账号（管理端只做兜底，正常由维保经理在小程序确认） */
export function confirmHandover(id, body) {
  return http.post(`/handovers/${id}/confirm`, body)
}

// ── C-12：GB/T 10060—2023 74 项移交检查清单（docs/15、docs/04 A.9）──

/** 74 项检查清单（进入查验中自动生成草稿，幂等）→ {check, items[74]} */
export function getChecklist(id) {
  return http.get(`/handovers/${id}/checklist`)
}

/** 提交查验：{items:[{itemNo,result,measuredValue?,failDesc?,naReason?,naNote?,photoFileIds?}], conditional?} */
export function submitChecklist(id, body) {
  return http.post(`/handovers/${id}/checklist/submit`, body)
}

/** 整改清单（按 itemNo 升序，1:1 映射检查项，不可增删） */
export function getRectifications(id) {
  return http.get(`/handovers/${id}/rectifications`)
}

/** 安装部逐条上传整改：{rectMeasure, afterPhotoFileId, rectifierName} */
export function rectifyItem(id, rectId, body) {
  return http.post(`/handovers/${id}/rectifications/${rectId}/rectify`, body)
}

/** 逐条复验（全部 PASS 才回待确认）：{result, recheckerId, recheckerName} */
export function recheckItem(id, rectId, body) {
  return http.post(`/handovers/${id}/rectifications/${rectId}/recheck`, body)
}

/** ☆项置 NA 的维保部经理审批：{approverId, approverName} */
export function naApprove(id, body) {
  return http.post(`/handovers/${id}/na-approve`, body)
}

/** 三张表单 PDF 导出（form=CHECK|RECT|INDEX），blob 直传 */
export function exportPdf(id, form) {
  return http.get(`/handovers/${id}/export-pdf`, { params: { form }, responseType: 'blob', timeout: 120000 })
}

/** 一梯一档归档目录（按移交单，7 条 + SHA-256，幂等） */
export function getArchive(id) {
  return http.get(`/handovers/${id}/archive`)
}
