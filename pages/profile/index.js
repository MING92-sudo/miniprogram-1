// 我的（M2，规格 §3.3）：单身份卡 + 切换工作台 + 功能入口 + 微信绑定 + 署名 + 退出。
// 合并旧 pages/mine + pages/unit/mine；菜单驱动渲染取代 role-view（M6 删 role-view）。
const auth = require('../../services/auth')
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const menus = require('../../config/menus')
const { getRoles, getPerms } = require('../../utils/auth')
const nav = require('../../utils/nav')
const { TOAST_TEXT } = require('../../constants/index')

const WECHAT_BOUND_KEY = 'em_wechat_bound'
const GROUPS = [
  { key: 'MAINT', label: '维保工作台', match: (r) => r.indexOf('MAINT') === 0 },
  { key: 'INST', label: '安装工作台', match: (r) => r === 'INSTALLER' || r.indexOf('INST') === 0 },
  { key: 'CLIENT', label: '使用单位', match: (r) => r.indexOf('CLIENT') === 0 },
  { key: 'PLATFORM', label: '平台管理', match: (r) => r === 'PLATFORM_ADMIN' }
]

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

definePage(ROUTES['/pages/profile/index'], {
  data: {
    userInfo: null, roleTexts: [], scope: '', workspaces: [],
    wechatBound: false, binding: false, versionText: '', adminEntries: []
  },
  onShow() {
    const app = getApp()
    const g = app.globalData || {}
    const roles = getRoles()
    const ws = g.workspace || menus.activeWorkspace(roles)
    const curGroup = (GROUPS.find((x) => x.match(ws)) || {}).key
    const workspaces = GROUPS.map((grp) => {
      const held = roles.filter(grp.match)
      if (!held.length) return null
      const rep = menus.ROLE_PRIORITY.find((r) => held.indexOf(r) > -1) || held[0]
      return { key: grp.key, label: grp.label, ws: rep, active: grp.key === curGroup }
    }).filter(Boolean)
    this.setData({
      userInfo: g.userInfo,
      roleTexts: roles.map((r) => menus.ROLE_TEXT[r] || r),
      scope: g.scope || '',
      workspaces,
      wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY),
      versionText: this.readVersion(),
      adminEntries: this.buildAdminEntries()
    })
  },
  // 管理入口按后端下发 perms 渲染（2026-11-15 裁决：渲染面=权限面）
  buildAdminEntries() {
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
      return mp.version ? '版本 ' + mp.version : (mp.envVersion === 'trial' ? '体验版' : '开发版')
    } catch (e) { return '' }
  },
  onSwitchWs(e) {
    const ws = e.currentTarget.dataset.ws
    if (e.currentTarget.dataset.active) return
    getApp().switchWorkspace(ws)
  },
  goMessage() { nav.open('/pages/message/index') },
  goOffline() { nav.open('/pages/offline/index') },
  goPassword() { nav.open('/pages/profile/password') },
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