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

// 签到（定位 + 水印自拍照片）
function checkin(orderId, data) {
  return post(`/work-orders/${orderId}/checkin`, data)
}

// 主维保生成双人动态码（每 5 秒刷新、60 秒有效、绑定工单、一次性；docs/03 §3.2 项6）
function issueDynamicCode(orderId) {
  return post(`/work-orders/${orderId}/dynamic-code`, {})
}

// 双人作业动态码校验（配合人员到场确认；不消费码，消费发生在配合人员签到时）
// 契约保留：独立动态码页已删（2026-11-16），校验/消费内联在 checkin 链路，端点保留
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

// 周期性条目"本次仍要执行"（TSG 附件A 周期性/季节性条目，docs/03 §6.1）
function runItemThisTime(orderId, itemId) {
  return post(`/work-orders/${orderId}/checklist/${itemId}/run-this-time`, {})
}

// 主维保/配合人员各自手机签字（docs/01 §3.7.5：严格各签各的，可重复签覆盖）
function signOrder(orderId, data) {
  return post(`/work-orders/${orderId}/signature`, data)
}

// 签退提交（后端自动转发平台 2.6，失败不自动重试）
function checkout(orderId, data) {
  return post(`/work-orders/${orderId}/checkout`, data,
    { idempotencyKey: 'co_' + orderId })
}

// 手动重报平台 2.6（仅 uploadStatus=FAILED 的记录，docs/04 A.7 P3 修订）
// 契约保留、小程序暂无入口：逐条人工重报入口在管理端（AGENTS §2.4 禁止自动重试）
function retryRecordUpload(recordId) {
  // 注意：此处不用稳定幂等键——重报必须真实重发，重放旧响应会吞掉重试
  return post(`/platform/records/${recordId}/reupload`, {})
}

// M1 记录生命周期（docs/04 V2.53）：配合退回/主管复核/发起使用单位签字/归档投影
// signatureRefuse/reviewRecord 契约保留、小程序暂无入口：复核在管理端（record:review）
function signatureRefuse(orderId) {
  return post(`/work-orders/${orderId}/signature-refuse`, {})
}

// 主管复核（契约保留，暂无页面调用）：后端 review 同 sign-request 按记录 id 定位，调用方须传 recordId
function reviewRecord(recordId, pass, reason) {
  return post(`/work-orders/${recordId}/review`, { pass, reason })
}

// 发起使用单位签字（P1 T8-A4：后端按记录 id 定位，调用方须传 recordId；路径保留 /work-orders 前缀契约）
function signRequest(recordId) {
  return post(`/work-orders/${recordId}/sign-request`, {})
}

// 员工内部读记录详情（C-24，后端 RecordController GET /records/{id}）：record:view 门禁 + V7 班组谓词，
// 内部全量投影（生命周期/版本链/shareToken/context/items 明细）；
// 客户门户读契约见 services/unit.getRecord（/unit/records/{id}，契约保留）
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
