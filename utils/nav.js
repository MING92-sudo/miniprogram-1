// 统一跳转（M2，规格 §1.2/§1.5）：tab=reLaunch 清栈；详情=navigateTo；所有路径来自白名单。
// 2026-11-16 审查清理：M6 后全部路由已建，删 TRANSITION_MAP 过渡映射与 parseScene 深链（零调用；扫码深链待 docs/12 C-17）。
const { ROUTES } = require('../config/routes')
const { resolveHome } = require('../config/menus')
const { getRoles, getHome } = require('./auth')

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

// 返回：有栈 navigateBack，无栈回当前工作台
function back() {
  const pages = getCurrentPages()
  if (pages.length > 1) wx.navigateBack()
  else reLaunch(currentHome())
}

function safeRedirect(raw) {
  if (!raw) return null
  let decoded = raw
  try { decoded = decodeURIComponent(raw) } catch (e) { decoded = raw }
  const path = decoded.split('?')[0]
  return ALLOWED_REDIRECTS.indexOf(path) > -1 ? decoded : null
}

module.exports = { open, back, safeRedirect }