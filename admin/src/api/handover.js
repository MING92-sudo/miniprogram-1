import http from './client'

export function listHandovers(params) {
  return http.get('/handovers', { params })
}

/** 起草选项：与小程序共用使用单位档案，handover:create 权限 */
export function getHandoverOptions() {
  return http.get('/handovers/options')
}

/** 移交单起草（handover:create=INST_PM/PLATFORM_ADMIN）：资料齐全进入 PENDING_INST_REVIEW */
export function createHandover(body) {
  return http.post('/handovers', body)
}

export function handoverDetail(id) {
  return http.get(`/handovers/${id}`)
}

/** 确认归档：确认人取当前登录账号（管理端只做兜底，正常由维保经理在小程序确认） */
export function confirmHandover(id, body) {
  return http.post(`/handovers/${id}/confirm`, body)
}

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
