// P3 客户账号认证（docs/14 §6.4）：独立于员工登录，先验口令 → 多单位选单位 → 客户令牌。
// 客户账号 principalType=CUSTOMER，仅可看本单位电梯/待签记录（/unit/** 兼容前缀 + /client/** 新端点）。
const { post, get } = require('../utils/request')

/** 客户口令登录：单单位直接返回令牌；多单位返回 needSelectUnit + selectionToken + units */
function clientLogin(phone, password) {
  return post('/auth/client/login', { phone, password }, { needAuth: false })
}

/** 微信一键登录（code 服务端换 openid，命中客户账号候选） */
function clientWxLogin(code) {
  return post('/auth/client/wx-login', { code }, { needAuth: false })
}

/** 选单位：selectionToken + useUnitId → 正式客户令牌 */
function selectUnit(selectionToken, useUnitId) {
  return post('/auth/client/select-unit', { useUnitId }, {
    needAuth: false,
    header: { Authorization: 'Bearer ' + selectionToken }
  })
}

/** 客户本人信息 */
function clientMe() {
  return get('/auth/client/me')
}

/** 客户自助改密（改后旧令牌失效，需重新登录） */
function clientChangePassword(oldPassword, newPassword) {
  return post('/auth/client/change-password', { oldPassword, newPassword })
}

module.exports = {
  clientLogin,
  clientWxLogin,
  selectUnit,
  clientMe,
  clientChangePassword
}
