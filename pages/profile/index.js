// 我的（屏 20 · 原型 V1.4 对齐）：身份卡（头像字+脱敏手机号）+ 我的角色（当前/兼任）+ 切换工作台（当前/无权限）
// + 功能入口 + 微信绑定 + 署名 + 退出。单账号多角色，权限取并集、数据范围逐角色判断（AGENTS §3.9）。
const auth = require('../../services/auth')
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const menus = require('../../config/menus')
const { getRoles, getPerms, isCustomerPrincipal } = require('../../utils/auth')
const nav = require('../../utils/nav')
const { TOAST_TEXT, PROFILE_TEXT, ROLE_DESC, BRAND } = require('../../constants/index')

const WECHAT_BOUND_KEY = 'em_wechat_bound'
const GROUPS = [
  { key: 'MAINT', label: '维保工作台', icon: 'screwdriver-wrench', match: (r) => r.indexOf('MAINT') === 0 },
  { key: 'INST', label: '安装部工作台', icon: 'helmet-safety', match: (r) => r === 'INSTALLER' || r.indexOf('INST') === 0 },
  { key: 'CLIENT', label: '使用单位工作台', icon: 'building', match: (r) => r.indexOf('CLIENT') === 0 },
  { key: 'PLATFORM', label: '平台管理', icon: 'server', match: (r) => r === 'PLATFORM_ADMIN' || r === 'ADMIN' }
]

function maskPhone(p) {
  const s = String(p || '')
  if (s.length < 7) return s
  return s.slice(0, 3) + '****' + s.slice(-4)
}
function wxLoginCode() {
  return new Promise((resolve, reject) => { wx.login({ success: (r) => resolve(r.code || ''), fail: reject }) })
}

definePage(ROUTES['/pages/profile/index'], {
  data: {
    userInfo: null, avatarChar: '', maskedPhone: '', roleList: [], scope: '', workbenchList: [],
    wechatBound: false, binding: false, versionText: '', adminEntries: [], text: PROFILE_TEXT, appName: BRAND.APP_NAME
  },
  onShow() {
    const app = getApp()
    const g = app.globalData || {}
    const roles = getRoles()
    const ws = g.workspace || menus.activeWorkspace(roles)
    const curGroup = (GROUPS.find((x) => x.match(ws)) || {}).key
    const u = g.userInfo || {}
    const roleList = roles.map((r) => ({ code: r, name: menus.ROLE_TEXT[r] || r, desc: ROLE_DESC[r] || '', current: r === ws }))
    const workbenchList = GROUPS.map((grp) => {
      const held = roles.filter(grp.match)
      const rep = menus.ROLE_PRIORITY.find((r) => held.indexOf(r) > -1) || held[0] || ''
      const state = grp.key === curGroup ? 'current' : (held.length ? 'held' : 'none')
      return { key: grp.key, label: grp.label, icon: grp.icon, ws: rep, state }
    }).filter((g) => g.state !== 'none') // 2026-11-20 真机反馈⑤：无权限工作台不渲染（权限面=渲染面），不再显示「无权限」占位行
    this.setData({
      userInfo: u,
      avatarChar: (u.name || '').charAt(0) || '我',
      maskedPhone: maskPhone(u.phone || u.account),
      roleList,
      scope: g.scope || '',
      workbenchList,
      customer: isCustomerPrincipal(),
      wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY),
      versionText: this.readVersion(),
      adminEntries: this.buildAdminEntries()
    })
  },
  // P1 T8-B3：客户主体不渲染任何管理入口——矩阵已收回内部码（T2），此处再防旧会话缓存的过期 perms
  buildAdminEntries() {
    if (isCustomerPrincipal()) return []
    const perms = getPerms() || []
    const entries = []
    if (perms.indexOf('project:view') > -1) entries.push({ key: 'projects', text: '项目台账', path: '/pkg-admin/pages/projects/index' })
    if (perms.indexOf('contract:view') > -1) entries.push({ key: 'contracts', text: '合同台账', path: '/pkg-admin/pages/contracts/index' })
    if (perms.indexOf('role:assign') > -1) entries.push({ key: 'roles', text: '角色矩阵', path: '/pkg-admin/pages/roles/index' })
    return entries
  },
  goAdminEntry(e) { nav.open(e.currentTarget.dataset.path) },
  readVersion() {
    try {
      const mp = wx.getAccountInfoSync().miniProgram
      return mp.version ? 'V' + mp.version : (mp.envVersion === 'trial' ? '体验版' : '开发版')
    } catch (e) { return '' }
  },
  onSwitchWs(e) {
    const state = e.currentTarget.dataset.state
    if (state !== 'held') return
    getApp().switchWorkspace(e.currentTarget.dataset.ws)
  },
  goMessage() { nav.open('/pages/message/index') },
  goOffline() { nav.open('/pages/offline/index') },
  goPassword() { nav.open('/pages/profile/password') },
  goMaskDoc() { wx.showModal({ title: PROFILE_TEXT.MASK, content: PROFILE_TEXT.MASK_CONTENT, showCancel: false, confirmText: '知道了' }) },
  async onBindWechat() {
    if (this.data.binding) return
    if (this.data.wechatBound) {
      wx.showModal({ title: '微信一键登录', content: '当前微信已绑定，可在登录页用微信免密登录。', showCancel: false, confirmText: '知道了' })
      return
    }
    this.setData({ binding: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      await auth.bindWeChat(code)
      wx.setStorageSync(WECHAT_BOUND_KEY, true)
      this.setData({ wechatBound: true })
      wx.showToast({ title: '微信已绑定', icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ binding: false })
  },
  async onLogout() {
    if (this._loggingOut) return
    this._loggingOut = true
    try { await auth.logout() } catch (e) { /* 本地清理不依赖接口成功 */ }
    getApp().logout()
    setTimeout(() => wx.reLaunch({ url: '/pages/login/index' }), 300)
  }
})
