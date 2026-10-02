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

// 急修单闭环：处理结果 + 使用单位安全管理员签字（docs/01 §3.9.2）
function closeFault(id, data) {
  return post(`/faults/${id}/close`, {
    result: data.result,
    signature: data.signature
  })
}

module.exports = {
  reportFault,
  getFaultList,
  getFaultDetail,
  closeFault
}
