// 鉴权与登录（docs/04 接口文档 B）
const { post } = require('../utils/request')
const { saveAuth } = require('../utils/auth')

// 微信授权登录：code + 手机号授权码 → JWT + 角色
// 第三参 role 仅 Mock 模式用于选择演示账号，真实后端忽略
function wxLogin(code, phoneCode, role) {
  return post('/auth/wx-login', { code, phoneCode, role }, { needAuth: false })
}

// 绑定维保人员档案（手机号匹配）
function bindEmployee(data) {
  return post('/auth/bind-employee', data, { needAuth: false })
}

// 绑定使用单位（邀请码）
function bindUseUnit(data) {
  return post('/auth/bind-use-unit', data, { needAuth: false })
}

// 退出登录
function logout() {
  return post('/auth/logout', {})
}

// 登录成功统一落库
function applyLoginResult(data) {
  saveAuth({
    token: data.token,
    userInfo: data.userInfo || null,
    role: data.role
  })
  return data
}

module.exports = {
  wxLogin,
  bindEmployee,
  bindUseUnit,
  logout,
  applyLoginResult
}
