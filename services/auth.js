// 鉴权与登录（docs/04 接口文档 B）
const { post } = require('../utils/request')
const { saveAuth } = require('../utils/auth')

// 微信授权登录：code + 手机号授权码 → JWT + 角色
// code 由后端 jscode2session 换真实 openid 后按 openid 查账号（P0 修复：不再依赖 X-WX-OPENID 请求头）
// 第三参 role 仅 Mock 模式用于选择演示账号，真实后端忽略
function wxLogin(code, phoneCode, role) {
  return post('/auth/wx-login', { code, phoneCode, role }, { needAuth: false })
}

// 账号密码登录（用户需求：账号由维保单位系统分配，手机号为账号）
function accountLogin(phone, password) {
  return post('/auth/login', { phone: phone, password: password }, { needAuth: false })
}

// 登录后绑定微信登录（wx.login code → 后端 jscode2session 换真实 openid 并写入当前账号）
// ★ 必须带登录态（needAuth=true）：后端按 Bearer JWT 定位"要绑定的账号"，
//   缺 Authorization 时后端返回 401，绑定不会落库（历史缺陷：静默返回 ok，微信一键登录永远用不了）
function bindWeChat(code) {
  return post('/auth/bind-wechat', { code: code }, { needAuth: true })
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
  accountLogin,
  bindWeChat,
  wxLogin,
  bindEmployee,
  bindUseUnit,
  logout,
  applyLoginResult
}
