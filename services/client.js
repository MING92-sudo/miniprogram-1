// P3 客户账号（docs/14 §6.4）：登录已并入统一入口 /auth/login（服务 auth.accountLogin），
// 本模块只保留「登录后」能力——多单位选单位、本人信息、自助改密。
// 客户账号 principalType=CUSTOMER，仅可看本单位电梯/待签记录（/unit/** 兼容前缀 + /client/** 新端点）。
const { post, get } = require('../utils/request')

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
  selectUnit,
  clientMe,
  clientChangePassword
}
