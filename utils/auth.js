// 登录态本地存储管理
const TOKEN_KEY = 'em_token'
const USER_KEY = 'em_user'
const ROLE_KEY = 'em_role'
// M1 单账号多角色：规范角色集/能力码/默认工作台（统一登录响应扩展字段）
const ROLES_KEY = 'em_roles'
const PERMS_KEY = 'em_perms'
const WORKSPACE_KEY = 'em_workspace'
const HOME_KEY = 'em_home'
const SCOPE_KEY = 'em_scope'

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

// 保存登录态（/auth/login 返回；M1 扩展 roles/perms/activeWorkspace/home）
function saveAuth({ token, userInfo, role, roles, perms, activeWorkspace, home, scope }) {
  wx.setStorageSync(TOKEN_KEY, token)
  wx.setStorageSync(USER_KEY, userInfo)
  wx.setStorageSync(ROLE_KEY, role)
  wx.setStorageSync(ROLES_KEY, roles || [role])
  wx.setStorageSync(PERMS_KEY, perms || [])
  wx.setStorageSync(WORKSPACE_KEY, activeWorkspace || role)
  wx.setStorageSync(HOME_KEY, home || '')
  wx.setStorageSync(SCOPE_KEY, scope || '')
}

function getRoles() {
  return wx.getStorageSync(ROLES_KEY) || []
}

function getPerms() {
  return wx.getStorageSync(PERMS_KEY) || []
}

function getWorkspace() {
  return wx.getStorageSync(WORKSPACE_KEY) || ''
}

function getHome() {
  return wx.getStorageSync(HOME_KEY) || ''
}

function getScope() {
  return wx.getStorageSync(SCOPE_KEY) || ''
}

function clearAuth() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
  wx.removeStorageSync(ROLE_KEY)
  wx.removeStorageSync(ROLES_KEY)
  wx.removeStorageSync(PERMS_KEY)
  wx.removeStorageSync(WORKSPACE_KEY)
  wx.removeStorageSync(HOME_KEY)
  wx.removeStorageSync(SCOPE_KEY)
}

module.exports = {
  getToken,
  getUserInfo,
  getRole,
  getRoles,
  getPerms,
  getWorkspace,
  getHome,
  getScope,
  isLoggedIn,
  saveAuth,
  clearAuth
}
