// 鉴权与登录（docs/04 接口文档 B）
const { post } = require('../utils/request')
const { saveAuth } = require('../utils/auth')

// 微信一键登录已启用（登录页仅本机已绑定账号显示，R5）；bindWeChat 入口在「我的」页。
// bindEmployee/bindUseUnit 已于 2026-11-20 随后端端点一并删除（角色切换收敛为多角色并集，docs/14）。
// 微信授权登录：code + 手机号授权码 → JWT + 角色
// 第三参 role 仅 Mock 模式用于选择演示账号，真实后端忽略
function wxLogin(code, phoneCode, role) {
  return post('/auth/wx-login', { code, phoneCode, role }, { needAuth: false })
}

// 用户需求④：登录用户自助改密（随机初始密码首次登录后修改）
function changePassword(oldPassword, newPassword) {
  return post('/auth/change-password', { oldPassword: oldPassword, newPassword: newPassword })
}
// 忘记密码：微信验证重置（code 服务端换 openid 匹配绑定账号；未绑定由页面引导管理员重置）
function resetPasswordByWechat(code, newPassword) {
  return post('/auth/reset-password-by-wechat', { code: code, newPassword: newPassword }, { needAuth: false })
}
// 账号密码登录（用户需求：账号由维保单位系统分配，手机号为账号）
function accountLogin(phone, password) {
  return post('/auth/login', { phone: phone, password: password }, { needAuth: false })
}

// 登录后绑定微信登录（wx.login code → openid 与账号关联）
function bindWeChat(code) {
  return post('/auth/bind-wechat', { code: code }, { needAuth: true })
}

// 退出登录
function logout() {
  return post('/auth/logout', {})
}

// 登录成功统一落库（M1：透传 roles/perms/activeWorkspace/home；P1 T8：透传 principalType）
function applyLoginResult(data) {
  saveAuth({
    token: data.token,
    userInfo: data.userInfo || null,
    role: data.role,
    roles: data.roles,
    perms: data.perms,
    activeWorkspace: data.activeWorkspace,
    home: data.home,
    scope: data.scope,
    principalType: data.principalType
  })
  return data
}

module.exports = {
  changePassword: changePassword,
  resetPasswordByWechat,
  accountLogin,
  bindWeChat,
  wxLogin,
  logout,
  applyLoginResult
}
