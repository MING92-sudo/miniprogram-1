// 知识库模块
const { get } = require('../utils/request')

// 知识条目检索（keyword 模糊匹配标题）
function getKnowledgeList(params) {
  return get('/knowledge', params)
}

module.exports = {
  getKnowledgeList
}
