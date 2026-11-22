// 工单模块（列表/详情/签到/清单/签退/平台重报）
const { get, post } = require('../utils/request')

// 工单列表（分页 page 从 1 开始，size 默认 20）
function getOrderList(params) {
  return get('/work-orders', params)
}

// 工单详情
function getOrderDetail(orderId) {
  return get(`/work-orders/${orderId}`)
}

// 扫码识别电梯 → 关联工单
function resolveByElevatorCode(elevatorCode) {
  return get('/work-orders/resolve-by-elevator', { elevatorCode })
}

function checkin(orderId, data) {
  return post(`/work-orders/${orderId}/checkin`, data)
}

function partnerCandidates(orderId) {
  return get(`/work-orders/${orderId}/partner-candidates`)
}

function assignPartner(orderId, employeeId) {
  return post(`/work-orders/${orderId}/assign-partner`, { employeeId })
}

// 主维保生成双人动态码（每 5 秒刷新、60 秒有效、绑定工单、一次性；
function issueDynamicCode(orderId) {
  return post(`/work-orders/${orderId}/dynamic-code`, {})
}

// 双人作业动态码校验（配合人员到场确认；
function verifyDynamicCode(orderId, data) {
  return post(`/work-orders/${orderId}/dynamic-code/verify`, data)
}

// 获取作业清单（含检查项）
function getChecklist(orderId) {
  return get(`/work-orders/${orderId}/checklist`)
}

// 提交检查项填写结果
function submitChecklistItem(orderId, itemId, data) {
  return post(`/work-orders/${orderId}/checklist/${itemId}`, data)
}

function runItemThisTime(orderId, itemId) {
  return post(`/work-orders/${orderId}/checklist/${itemId}/run-this-time`, {})
}

function signOrder(orderId, data) {
  return post(`/work-orders/${orderId}/signature`, data)
}

// 签退提交（后端自动转发平台 2.6，失败不自动重试）
function checkout(orderId, data, options) {
  const idem = options && options.idempotencyKey
  return post(`/work-orders/${orderId}/checkout`, data,
    { idempotencyKey: idem || 'co_' + orderId })
}

// 手动重报平台 2.6契约保留、小程序暂无入口：逐条人工重报入口在管理端（AGENTS §2.4 禁止自动重试）
function retryRecordUpload(recordId) {
  // 注意：此处不用稳定幂等键——重报必须真实重发，重放旧响应会吞掉重试
  return post(`/platform/records/${recordId}/reupload`, {})
}

function signatureRefuse(orderId) {
  return post(`/work-orders/${orderId}/signature-refuse`, {})
}

// 主管复核（契约保留，暂无页面调用）：后端 review 同 sign-request 按记录 id 定位，调用方须传 recordId
function reviewRecord(recordId, pass, reason) {
  return post(`/work-orders/${recordId}/review`, { pass, reason })
}

function signRequest(recordId) {
  return post(`/work-orders/${recordId}/sign-request`, {})
}

function getRecordDetail(recordId) {
  return get(`/records/${recordId}`)
}

// 归档投影（PDF 重签 URL + SHA-256）：同 sign-request，后端按记录 id 定位
function getRecordArchive(recordId) {
  return get(`/work-orders/${recordId}/archive`)
}

module.exports = {
  getOrderList,
  getOrderDetail,
  resolveByElevatorCode,
  checkin,
  partnerCandidates,
  assignPartner,
  issueDynamicCode,
  verifyDynamicCode,
  getChecklist,
  submitChecklistItem,
  runItemThisTime,
  signOrder,
  checkout,
  retryRecordUpload,
  signatureRefuse,
  reviewRecord,
  signRequest,
  getRecordArchive,
  getRecordDetail
}
