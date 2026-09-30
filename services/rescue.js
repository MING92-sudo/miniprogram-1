// 困人救援模块
const { get, post } = require('../utils/request')

// 救援登记
function createRescue(data) {
  return post('/rescues', data)
}

// 救援列表
function getRescueList(params) {
  return get('/rescues', params)
}

// 救援详情
function getRescueDetail(id) {
  return get(`/rescues/${id}`)
}

module.exports = {
  createRescue,
  getRescueList,
  getRescueDetail
}
