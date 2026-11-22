const { ROUTES } = require('../config/routes')
const { resolveHome } = require('../config/menus')
const { getRoles, getHome, isLoggedIn } = require('./auth')

// 无旧等价页兜底（pkg-admin 等）→ profile
const FALLBACK = '/pages/profile/index'

// 登录后回跳白名单（蓝图 L436-443 + 三 home + profile，防开放重定向）
const ALLOWED_REDIRECTS = [
  '/pages/maint/home/index', '/pages/install/home/index', '/pages/client/home/index',
  '/pages/elevator/detail/index', '/pkg-client/pages/sign/index', '/pkg-maintenance/pages/record-detail/index',
  '/pages/profile/index'
]

function splitUrl(url) {
  const s = String(url || '')
  const i = s.indexOf('?')
  return i < 0 ? { path: s, query: '' } : { path: s.slice(0, i), query: s.slice(i) }
}

function currentHome() {
  return getHome() || resolveHome(getRoles()) || FALLBACK
}

function reLaunch(url) { wx.reLaunch({ url }) }

// 统一打开：白名单校验 → tab/详情分流（未建路由仅留兜底提示）
function open(url) {
  const { path, query } = splitUrl(url)
  const meta = ROUTES[path]
  if (!meta) {
    wx.showToast({ title: '页面不存在', icon: 'none' })
    return
  }
  if (meta.built) {
    if (meta.tab) return reLaunch(path + query)
    wx.navigateTo({
      url: path + query,
      fail: () => reLaunch(path + query)
    })
    return
  }
  wx.showToast({ title: '该功能将在后续版本开放', icon: 'none' })
  setTimeout(() => reLaunch(FALLBACK), 300)
}

// 返回：有栈 navigateBack；无栈已登录回当前工作台，未登录回预览页（避免弹回登录页死循环）
function back() {
  const pages = getCurrentPages()
  if (pages.length > 1) { wx.navigateBack(); return }
  if (isLoggedIn()) { reLaunch(currentHome()); return }
  reLaunch('/pages/preview/index')
}

function safeRedirect(raw) {
  if (!raw) return null
  let decoded = raw
  try { decoded = decodeURIComponent(raw) } catch (e) { decoded = raw }
  const path = decoded.split('?')[0]
  return ALLOWED_REDIRECTS.indexOf(path) > -1 ? decoded : null
}

module.exports = { open, back, safeRedirect }