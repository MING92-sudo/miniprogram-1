// 维保记录签名确认（取消独立使用单位端：链接分享远程签字 / 本机代签）
const { get, post } = require('../utils/request')

// 记录完整视图（GET /unit/records/:id，与维保记录预览页/使用单位确认同源，docs/03 V2.1 §3.3）
function getRecord(id) {
  return get('/unit/records/' + id)
}

// 打开签名链接：校验令牌，返回记录摘要与确认状态（无需登录）
function getSignView(id, token) {
  return get('/unit/records/' + id + '/sign-view?token=' + token, { needAuth: false })
}

// 安全管理员签字确认（签名图 + 满意度）
function confirmByToken(id, token, data) {
  return post('/unit/records/' + id + '/confirm-by-token?token=' + token, data, { needAuth: false })
}

module.exports = {
  getRecord,
  getSignView,
  confirmByToken
}
