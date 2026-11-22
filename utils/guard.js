const { isLoggedIn, getToken, getRoles, getPerms } = require('./auth')
const { ROLE_TEXT } = require('../config/menus')

let redirecting = false

// 跳转登录页（防抖与路由竞态规避；可选 path 拼 redirect 回跳）
function redirectToLogin(path) {
  if (redirecting) return
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1] : null
  if (current && current.route === 'pages/login/index') return
  redirecting = true
  // 延迟到首屏路由(routeDone)完成后再销毁 webview，规避「routeDone with a webviewId not found」竞态（真机白屏）。
  const url = path ? '/pages/login/index?redirect=' + encodeURIComponent(path) : '/pages/login/index'
  setTimeout(() => {
    wx.reLaunch({ url, complete: () => { redirecting = false } })
  }, 300)
}

function ensureLogin() {
  if (isLoggedIn()) return true
  redirectToLogin()
  return false
}

// 角色/权限判定（perm 可为数组 any-of，裁决 R6）
function canAccess(meta, session) {
  if (!meta.auth) return true
  if (!session || !session.token) return false
  const roles = session.roles || []
  const perms = session.perms || []
  if (meta.roles && !meta.roles.some((r) => roles.indexOf(r) > -1)) return false
  if (meta.perm) {
    const need = Array.isArray(meta.perm) ? meta.perm : [meta.perm]
    if (!need.some((p) => perms.indexOf(p) > -1)) return false
  }
  return true
}

// 无令牌→登录(带 redirect)；无权限→403（不回显目标 id，裁决 R3；拒绝详情写 globalData 供 403 页中文化）
function ensureAuth(meta) {
  const session = { token: getToken(), roles: getRoles(), perms: getPerms() }
  if (meta.auth && !session.token) {
    redirectToLogin(meta.path)
    return false
  }
  if (!canAccess(meta, session)) {
    const app = getApp()
    if (app && app.globalData) {
      app.globalData.lastDeny = {
        capability: meta.name,
        allowRoles: (meta.roles || []).map((r) => ROLE_TEXT[r] || r).join('、') || '请联系管理员'
      }
    }
    // 延迟到首屏路由完成后再 reLaunch，规避真机「routeDone with a webviewId not found」竞态白屏（同 redirectToLogin）
    setTimeout(() => { wx.reLaunch({ url: '/pages/403/index' }) }, 300)
    return false
  }
  return true
}

// 页面包装：onLoad/onShow 双层校验（防后台切回时权限已变更，蓝图 L418-430）
function definePage(meta, options) {
  Page(Object.assign({}, options, {
    onLoad(query) {
      if (!ensureAuth(meta)) return
      if (options.onLoad) options.onLoad.call(this, query)
    },
    onShow() {
      if (!ensureAuth(meta)) return
      if (options.onShow) options.onShow.call(this)
    }
  }))
}

module.exports = { ensureLogin, redirectToLogin, canAccess, ensureAuth, definePage }