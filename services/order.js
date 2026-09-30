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
  return post('/work-orders/resolve-by-elevator', { elevatorCode })
}

// 签到（定位 + 水印自拍照片）
function checkin(orderId, data) {
  return post(`/work-orders/${orderId}/checkin`, data)
}

// 双人作业动态码校验
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

// 签退提交（后端自动转发平台 2.6，失败不自动重试）
function checkout(orderId, data) {
  return post(`/work-orders/${orderId}/checkout`, data)
}

// 手动重报平台 2.6（仅 uploadStatus=FAILED 的记录，docs/04 A.7 P3 修订）
function retryRecordUpload(recordId) {
  return post(`/platform/records/${recordId}/reupload`, {})
}

module.exports = {
  getOrderList,
  getOrderDetail,
  resolveByElevatorCode,
  checkin,
  verifyDynamicCode,
  getChecklist,
  submitChecklistItem,
  runItemThisTime,
  checkout,
  retryRecordUpload
}
