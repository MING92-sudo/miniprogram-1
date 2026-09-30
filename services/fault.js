// 故障上报与闭环模块
const { get, post } = require('../utils/request')

// 故障上报
function reportFault(data) {
  return post('/faults', data)
}

// 故障列表
function getFaultList(params) {
  return get('/faults', params)
}

// 故障详情
function getFaultDetail(id) {
  return get(`/faults/${id}`)
}

// 故障闭环处理
function closeFault(id, data) {
  return post(`/faults/${id}/close`, data)
}

module.exports = {
  reportFault,
  getFaultList,
  getFaultDetail,
  closeFault
}
