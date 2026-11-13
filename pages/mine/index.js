// 我的：用户信息 + 按角色显隐的功能入口（docs/14 §7 小程序权限矩阵）
const auth = require('../../services/auth')
const { isLoggedIn } = require('../../utils/auth')
const { viewOf } = require('../../utils/role-view')
const {
  isUnitLoggedIn,
  getUnitSession,
  clearUnitSession,
  bindUnitWechat
} = require('../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../constants/index')

// 本机记录「已开启微信绑定」：登录页据此决定是否显示微信一键登录入口（未开启不占位）
const WECHAT_BOUND_KEY = 'em_wechat_bound'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

// 证件有效期提醒（docs/03 §3.2 项18）：已过期 / 60 天内到期 置顶提示，其余仅展示有效期
function certReminder(user) {
  const end = user && user.workEndDate ? String(user.workEndDate).slice(0, 10) : ''
  if (!end) return { text: '', level: '' }
  const ts = new Date(end.replace(/-/g, '/')).getTime()
  if (!ts) return { text: '', level: '' }
  const days = Math.floor((ts - Date.now()) / 86400000)
  if (days < 0) return { text: '证件已于 ' + end + ' 过期，请联系管理员更新人员档案', level: 'expired' }
  if (days <= 60) return { text: '证件将于 ' + end + ' 到期（剩 ' + days + ' 天），请及时换证', level: 'soon' }
  return { text: '证件有效期至 ' + end, level: 'ok' }
}

// 角色可见菜单：作业入口按角色给（管理员/商务等巡视岗没有现场作业入口），
// 离线补传与改密对所有人保留；真正的数据范围由后端谓词收敛，这里只决定“看不看得见入口”。
function buildMenus(role) {
  const v = viewOf(role)
  const menus = v.menus.map(function (tile) {
    return {
      title: tile.title,
      url: tile.url,
      desc: tile.title.indexOf('急修') > -1 ? '现场登记、闭环跟踪' : ''
    }
  })
  menus.push({ title: '离线缓存管理', url: '/pages/mine/offline', desc: '弱网数据补传' })
  menus.push({ title: '修改密码', url: '/pages/mine/password', desc: '随机初始密码首次登录后修改' })
  return menus
}

Page({
  data: {
    guest: false,
    userInfo: null,
    roleText: '',
    unitLogged: false,
    unitName: '',
    unitWechatBound: false,
    inviteCode: '',
    binding: false,
    wechatBound: false,
    scopeText: '',
    versionText: '',
    menus: [],
    certText: '',
    certLevel: ''
  },

  onShow() {
    if (!this._authReady) return
    if (!this.guard()) return
    this.refresh()
  },

  onReady() {
    this._authReady = true
    if (!this.guard()) return
    this.refresh()
  },

  // 未登录：展示登录引导空状态，不强制跳转（登录规范：先浏览后登录）
  // 门户登录态（与员工登录态无关）：决定是否显示「使用单位门户」卡片
  refreshUnitState() {
    const logged = isUnitLoggedIn()
    const session = logged ? (getUnitSession() || {}) : {}
    this.setData({
      unitLogged: logged,
      unitName: session.unitName || '',
      unitWechatBound: !!session.wechatBound
    })
    return logged
  },

  onInviteInput(e) {
    this.setData({ inviteCode: e.detail.value })
  },

  // 员工微信绑定（POST /auth/bind-wechat，需 Bearer）：绑定后登录页出现「微信一键登录」
  async onBindWechat() {
    if (this.data.binding) return
    if (this.data.wechatBound) {
      wx.showModal({
        title: '微信一键登录',
        content: '当前微信已绑定，可在登录页用微信免密登录。',
        showCancel: false,
        confirmText: '知道了'
      })
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

  // 使用单位微信免密绑定（邀请码 + wx.login code）：绑定后可微信免密进门户
  async onUnitWechatBind() {
    if (this.data.binding) return
    const inviteCode = String(this.data.inviteCode || '').trim()
    if (!inviteCode) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.INVITE_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ binding: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      await bindUnitWechat({ inviteCode: inviteCode, code: code })
      const session = getUnitSession() || {}
      session.wechatBound = true
      wx.setStorageSync('unit_info', session)
      wx.setStorageSync(WECHAT_BOUND_KEY, true)
      this.setData({ unitWechatBound: true, inviteCode: '' })
      wx.showToast({ title: UNIT_PORTAL_TEXT.BIND_SUCCESS, icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ binding: false })
  },

  goUnitHome() {
    wx.navigateTo({ url: '/pages/unit/home' })
  },

  async logoutUnit() {
    clearUnitSession()
    this.setData({ unitLogged: false, unitName: '' })
    wx.showToast({ title: '已退出门户', icon: 'none' })
  },

  guard() {
    if (isLoggedIn()) {
      this.setData({ guest: false })
      this.refreshUnitState()
      return true
    }
    this.setData({ guest: true })
    this.refreshUnitState()
    return false
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  refresh() {
    const app = getApp()
    const role = app.globalData.role
    let versionText = ''
    try {
      const mp = wx.getAccountInfoSync().miniProgram
      versionText = mp.version ? '版本 ' + mp.version : mp.envVersion === 'trial' ? '体验版' : '开发版'
    } catch (e) {
      versionText = ''
    }
    const v = viewOf(role)
    const cert = certReminder(app.globalData.userInfo)
    this.setData({
      userInfo: app.globalData.userInfo,
      roleText: v.dept ? v.dept + ' · ' + v.roleText : v.roleText,
      scopeText: v.scope,
      versionText,
      menus: buildMenus(role),
      certText: cert.text,
      certLevel: cert.level,
      wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY)
    })
  },

  onMenuTap(e) {
    wx.navigateTo({ url: e.currentTarget.dataset.url })
  },

  async onLogout() {
    // 防重：logout 接口有延迟，连点会叠加两次 reLaunch 触发路由竞态
    if (this._loggingOut) return
    this._loggingOut = true
    try {
      await auth.logout()
    } catch (e) {
      // 本地清理不依赖接口成功
    }
    getApp().logout()
    // 延迟 reLaunch 避开路由竞态（routeDone with webviewId is not found）
    setTimeout(() => wx.reLaunch({ url: '/pages/login/index' }), 300)
  }
})
