// 登录态本地存储管理
const TOKEN_KEY = 'em_token'
const USER_KEY = 'em_user'
const ROLE_KEY = 'em_role'
const ROLES_KEY = 'em_roles'
const PERMS_KEY = 'em_perms'
const WORKSPACE_KEY = 'em_workspace'
const HOME_KEY = 'em_home'
const SCOPE_KEY = 'em_scope'
const PRINCIPAL_KEY = 'em_principal'

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

function saveAuth({ token, userInfo, role, roles, perms, activeWorkspace, home, scope, principalType }) {
  wx.setStorageSync(TOKEN_KEY, token)
  wx.setStorageSync(USER_KEY, userInfo)
  wx.setStorageSync(ROLE_KEY, role)
  wx.setStorageSync(ROLES_KEY, roles || [role])
  wx.setStorageSync(PERMS_KEY, perms || [])
  wx.setStorageSync(WORKSPACE_KEY, activeWorkspace || role)
  wx.setStorageSync(HOME_KEY, home || '')
  wx.setStorageSync(SCOPE_KEY, scope || '')
  wx.setStorageSync(PRINCIPAL_KEY, principalType || '')
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

function getPrincipalType() {
  return wx.getStorageSync(PRINCIPAL_KEY) || ''
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
  wx.removeStorageSync(PRINCIPAL_KEY)
}


const ROLE_ALIAS = {
  WORKER: 'MAINT_TECH', ASSISTANT: 'MAINT_TECH',
  UNIT_ADMIN: 'CLIENT_ADMIN', ADMIN: 'PLATFORM_ADMIN',
  INSTALL_MANAGER: 'INST_PM', INSTALL_CLERK: 'INST_CLERK', INSTALL_WORKER: 'INSTALLER',
  MAINT_MANAGER: 'MAINT_MGR'
}

// 会话角色集（规范 roles 数组 + 单角色及其别名），供页面岗位门控统一判定
function getSessionRoles() {
  const out = (getRoles() || []).slice()
  const single = getRole()
  if (single) {
    if (out.indexOf(single) < 0) out.push(single)
    if (ROLE_ALIAS[single] && out.indexOf(ROLE_ALIAS[single]) < 0) out.push(ROLE_ALIAS[single])
  }
  return out
}

function hasAnyRole(list) {
  const session = getSessionRoles()
  return (list || []).some(function (r) { return session.indexOf(r) > -1 })
}

function isCustomerPrincipal() {
  const pt = getPrincipalType()
  if (pt) return pt === 'CUSTOMER'
  const roles = getRoles() || []
  if (!roles.length) return false
  return roles.every(function (r) {
    return ['CLIENT_ADMIN', 'CLIENT_SIGNER'].indexOf(ROLE_ALIAS[r] || r) > -1
  })
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
  getPrincipalType,
  isLoggedIn,
  saveAuth,
  clearAuth,
  getSessionRoles,
  hasAnyRole,
  isCustomerPrincipal
}

