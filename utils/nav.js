// 统一跳转（M2，规格 §1.2/§1.5）：tab=reLaunch 清栈；详情=navigateTo；
// 未建路由走过渡映射回旧页（裁决 R4，各域迁移完即删对应行，M6 删全表）；所有路径来自白名单。
const { ROUTES } = require('../config/routes')
const { resolveHome } = require('../config/menus')
const { getRoles, getHome } = require('./auth')

// 新→旧过渡映射（M0 §4 反向）：M3/M4/M5 已建全部业务域，映射清空；pkg-admin（M6）无旧等价走 FALLBACK。
const TRANSITION_MAP = {}
// 无旧等价（pkg-admin 7 页 + record-sign + evaluate + handover upload/review）→ profile 兜底
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

// 统一打开：白名单校验 → 已建按 tab/详情分流 → 未建走过渡映射
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
  // 未建路由（M6 后全部已建，此分支仅留兜底）：过渡映射或 profile 兜底
  const old = TRANSITION_MAP[path]
  if (old) return reLaunch(old + query)
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

// 扫码场景深链解析（蓝图 L323-330）：q=route=<白名单路径>
function parseScene(q) {
  if (!q) return null
  let query = q
  try { query = decodeURIComponent(q) } catch (e) { query = q }
  const m = query.match(/(?:^|&)route=([^&]+)/)
  if (!m) return null
  const route = m[1]
  const path = route.split('?')[0]
  return ROUTES[path] ? route : null
}

module.exports = { open, back, safeRedirect, parseScene, TRANSITION_MAP, FALLBACK, ALLOWED_REDIRECTS }