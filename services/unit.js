// 使用单位确认端（安全管理员）
const { get, post } = require('../utils/request')

// 待确认记录列表
function getPendingRecords(params) {
  return get('/unit/records/pending', params)
}

// 记录查看
function getRecordDetail(id) {
  return get(`/unit/records/${id}`)
}

// 确认（手写签名 + 满意度评价）
function confirmRecord(id, data) {
  return post(`/unit/records/${id}/confirm`, data)
}

module.exports = {
  getPendingRecords,
  getRecordDetail,
  confirmRecord
}
