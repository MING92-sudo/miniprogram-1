// 首页看板汇总
const { get } = require('../utils/request')

function getHomeSummary() {
  return get('/home/summary')
}

module.exports = {
  getHomeSummary
}
