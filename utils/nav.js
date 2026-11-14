// 统一跳转（M2，规格 §1.2/§1.5）：tab=reLaunch 清栈；详情=navigateTo；
// 未建路由走过渡映射回旧页（裁决 R4，各域迁移完即删对应行，M6 删全表）；所有路径来自白名单。
const { ROUTES } = require('../config/routes')
const { resolveHome } = require('../config/menus')
const { getRoles, getHome, getWorkspace } = require('./auth')

// 新→旧过渡映射（M0 §4 反向）
const TRANSITION_MAP = {
  '/pages/order/list/index': '/pages/fault/list',
  '/pages/order/detail/index': '/pages/fault/detail',
  '/pages/order/report/index': '/pages/fault/report',
  '/pkg-maintenance/pages/orders/index': '/pages/order/list',
  '/pkg-maintenance/pages/task/index': '/pages/order/detail',
  '/pkg-maintenance/pages/checkin/index': '/pages/order/checkin',
  '/pkg-maintenance/pages/dynamic-code/index': '/pages/order/dynamic-code',
  '/pkg-maintenance/pages/checkout/index': '/pages/order/checkout',
  '/pkg-maintenance/pages/record-detail/index': '/pages/order/record-preview',
  '/pkg-maintenance/pages/plan/index': '/pages/calendar/index',
  '/pkg-maintenance/pages/record-archive/index': '/pages/order/record-preview',
  '/pkg-handover/pages/list/index': '/pages/handover/list',
  '/pkg-handover/pages/create/index': '/pages/handover/init',
  '/pkg-handover/pages/detail/index': '/pages/handover/detail',
  '/pkg-handover/pages/inspect/index': '/pages/handover/inspect',
  '/pkg-handover/pages/rectify/index': '/pages/handover/rectify',
  '/pkg-handover/pages/sign/index': '/pages/handover/confirm',
  '/pkg-handover/pages/filing-init/index': '/pages/filing/init',
  '/pkg-handover/pages/filing-detail/index': '/pages/filing/detail',
  '/pkg-handover/pages/filing-inspect/index': '/pages/filing/inspect',
  '/pkg-handover/pages/filing-confirm/index': '/pages/filing/confirm',
  '/pkg-client/pages/todo/index': '/pages/unit/records',
  '/pkg-client/pages/sign/index': '/pages/unit/sign',
  '/pkg-client/pages/orders/index': '/pages/unit/faults'
}
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
  // 未建：过渡映射回旧页（保留 query）
  let old = TRANSITION_MAP[path]
  if (path === '/pages/order/report/index' && String(getWorkspace()).indexOf('CLIENT') === 0) {
    old = '/pages/unit/report'
  }
  if (old) return reLaunch(old + query)
  wx.showToast({ title: '该功能将在后续版本开放', icon: 'none' })
  setTimeout(() => reLaunch(FALLBACK), 300)
}

// 旧页 wx.switchTab 的统一 shim（原生 tabBar 已删）：旧 tab 路径改 reLaunch，home/mine 落新壳
function switchTab(opts) {
  const url = (opts && opts.url) || ''
  const { path } = splitUrl(url)
  let target = path
  if (path === '/pages/home/index') target = currentHome()
  else if (path === '/pages/mine/index') target = '/pages/profile/index'
  reLaunch(target)
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

module.exports = { open, switchTab, back, safeRedirect, parseScene, TRANSITION_MAP, FALLBACK, ALLOWED_REDIRECTS }