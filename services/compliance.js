// 合规台账（TSG 法定项：自行检查 / 应急演练；数据暂存本地，不上报平台）
const { get, post } = require('../utils/request')

// 应急演练列表（含半年品种覆盖检查结果）
function getDrills() {
  return get('/drills')
}

// 登记应急演练
function createDrill(data) {
  return post('/drills', data)
}

// 自行检查台账（各电梯状态：已完成/未检/逾期未检）
function getInspects() {
  return get('/inspects')
}

// 自行检查项模板（年度维保项，docs/01 §3.17"不少于年度维保项"）
function getInspectTemplate() {
  return get('/inspects/template')
}

// 提交自行检查记录
function createInspect(data) {
  return post('/inspects', data)
}

module.exports = {
  getDrills,
  createDrill,
  getInspects,
  getInspectTemplate,
  createInspect
}
