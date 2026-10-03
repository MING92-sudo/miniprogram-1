// 我的：用户信息 + 按角色显隐的功能入口
const { ROLE } = require('../../constants/index')
const auth = require('../../services/auth')
const { ensureLogin } = require('../../utils/guard')

const ROLE_TEXT = {
  WORKER: '维保人员',
  ASSISTANT: '配合维保人员',
  LEADER: '班组长',
  UNIT_ADMIN: '使用单位安全管理员',
  ADMIN: '维保部管理员',
  SYS_ADMIN: '系统管理员'
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

// 角色可见菜单（用户需求：取消独立使用单位端，签字确认走分享链接/本机代签）
const STAFF_ROLES = [ROLE.WORKER, ROLE.ASSISTANT, ROLE.LEADER]

function buildMenus(role) {
  const menus = []
  if (STAFF_ROLES.indexOf(role) > -1) {
    menus.push({ title: '急修单', url: '/pages/fault/list', desc: '现场登记、闭环跟踪' })
  }
  menus.push({ title: '离线缓存管理', url: '/pages/mine/offline', desc: '弱网数据补传' })
  menus.push({ title: '修改密码', url: '/pages/mine/password', desc: '随机初始密码首次登录后修改' })
  return menus
}

Page({
  data: {
    userInfo: null,
    roleText: '',
    versionText: '',
    menus: [],
    certText: '',
    certLevel: ''
  },

  onShow() {
    // 首次 onShow 发生在 tab 路由进行中，此时守卫 reLaunch 会与当前路由竞态
    // （routeDone with a webviewId xxx is not found / 真机白屏），首次守卫延迟到 onReady
    if (!this._authReady) return
    if (!ensureLogin()) return
    this.refresh()
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.refresh()
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
    const cert = certReminder(app.globalData.userInfo)
    this.setData({
      userInfo: app.globalData.userInfo,
      roleText: ROLE_TEXT[role] || ROLE[role] || '未登录',
      versionText,
      menus: buildMenus(role),
      certText: cert.text,
      certLevel: cert.level
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
    wx.reLaunch({ url: '/pages/login/index' })
  }
})
