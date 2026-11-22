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
