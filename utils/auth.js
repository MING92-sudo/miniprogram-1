// 登录态本地存储管理
const TOKEN_KEY = 'em_token'
const USER_KEY = 'em_user'
const ROLE_KEY = 'em_role'

function getToken() {
  return wx.getStorageSync(TOKEN_KEY)
}

function getUserInfo() {
  return wx.getStorageSync(USER_KEY) || null
}

function getRole() {
  return wx.getStorageSync(ROLE_KEY)
}

function isLoggedIn() {
  return !!getToken()
}

// 保存登录态（/auth/wx-login 返回）
function saveAuth({ token, userInfo, role }) {
  wx.setStorageSync(TOKEN_KEY, token)
  wx.setStorageSync(USER_KEY, userInfo)
  wx.setStorageSync(ROLE_KEY, role)
}

function clearAuth() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
  wx.removeStorageSync(ROLE_KEY)
}

module.exports = {
  getToken,
  getUserInfo,
  getRole,
  isLoggedIn,
  saveAuth,
  clearAuth
}
