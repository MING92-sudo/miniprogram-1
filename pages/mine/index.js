// 我的（员工态）：用户信息 + 按角色显隐的功能入口（docs/14 §7 小程序权限矩阵）。
// 使用单位门户身份不用本页：门户态的「我的」是 pages/unit/mine（只含单位信息与设置项）。
const auth = require('../../services/auth')
const { isLoggedIn } = require('../../utils/auth')
const { viewOf } = require('../../utils/role-view')
const { isUnitLoggedIn } = require('../../services/unit')
const { TOAST_TEXT } = require('../../constants/index')

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
    wechatBound: false,
    binding: false,
    scopeText: '',
    versionText: '',
    menus: [],
    certText: '',
    certLevel: ''
  },

  onShow() {
    if (!this._authReady) return
    this.enterState()
  },

  onReady() {
    // M3：旧「我的」已被新 profile 取代，重定向到新壳（本页 M6 删除）
    wx.reLaunch({ url: '/pages/profile/index' })
  },

  // 单身份：门户身份不进本页（回门户首页）；员工态展示个人信息；都没有则登录引导
  enterState() {
    if (!isLoggedIn()) {
      if (isUnitLoggedIn()) {
        wx.reLaunch({ url: '/pages/unit/home' })
        return
      }
      this.setData({ guest: true, versionText: this.readVersion() })
      return
    }
    this.setData({ guest: false })
    this.refresh()
  },

  readVersion() {
    try {
      const mp = wx.getAccountInfoSync().miniProgram
      return mp.version ? '版本 ' + mp.version : mp.envVersion === 'trial' ? '体验版' : '开发版'
    } catch (e) {
      return ''
    }
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  refresh() {
    const app = getApp()
    const role = app.globalData.role
    const v = viewOf(role)
    const cert = certReminder(app.globalData.userInfo)
    this.setData({
      userInfo: app.globalData.userInfo,
      roleText: v.dept ? v.dept + ' · ' + v.roleText : v.roleText,
      scopeText: v.scope,
      versionText: this.readVersion(),
      menus: buildMenus(role),
      certText: cert.text,
      certLevel: cert.level,
      wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY)
    })
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
