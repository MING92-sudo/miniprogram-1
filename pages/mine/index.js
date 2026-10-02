// 我的：用户信息 + 按角色显隐的功能入口
const { ROLE } = require('../../constants/index')
const auth = require('../../services/auth')
const { ensureLogin } = require('../../utils/guard')
const { bindWeChat } = require('../../utils/bind-wechat')

const ROLE_TEXT = {
  WORKER: '维保人员',
  ASSISTANT: '配合维保人员',
  LEADER: '班组长',
  UNIT_ADMIN: '使用单位安全管理员',
  ADMIN: '维保部管理员',
  SYS_ADMIN: '系统管理员'
}

// 角色可见菜单（用户需求：取消独立使用单位端，签字确认走分享链接/本机代签）
const STAFF_ROLES = [ROLE.WORKER, ROLE.ASSISTANT, ROLE.LEADER]

function buildMenus(role) {
  const menus = []
  if (STAFF_ROLES.indexOf(role) > -1) {
    // 合规台账（自行检查 / 应急演练）
    menus.push({ title: '自行检查', url: '/pages/compliance/inspect', desc: '年度法定检查，定期检验前完成' })
    menus.push({ title: '应急演练', url: '/pages/compliance/drill', desc: '每半年至少 1 轮，覆盖全部在保品种' })
    menus.push({ title: '故障上报', url: '/pages/fault/report', desc: '现场故障登记' })
    menus.push({ title: '故障记录', url: '/pages/fault/list', desc: '上报记录与闭环跟踪' })
    menus.push({ title: '救援记录', url: '/pages/rescue/list', desc: '困人救援登记与跟踪' })
  }
  menus.push({ title: '知识库', url: '/pages/knowledge/index', desc: '作业手册与流程规范' })
  menus.push({ title: '重新绑定微信', action: 'rebindWeChat', desc: '账号关联当前微信后可一键登录' })
  menus.push({ title: '离线缓存管理', url: '/pages/mine/offline', desc: '弱网数据补传' })
  return menus
}

Page({
  data: {
    userInfo: null,
    roleText: '',
    menus: [],
    rebinding: false
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
    this.setData({
      userInfo: app.globalData.userInfo,
      roleText: ROLE_TEXT[role] || ROLE[role] || '未登录',
      menus: buildMenus(role)
    })
  },

  onMenuTap(e) {
    const ds = e.currentTarget.dataset
    if (ds.action === 'rebindWeChat') return this.onRebindWeChat()
    wx.navigateTo({ url: ds.url })
  },

  // 登录时的自动绑定可能失败（未授权、code 过期等），这里提供手动重试入口
  async onRebindWeChat() {
    if (this.data.rebinding) return
    this.setData({ rebinding: true })
    try {
      await bindWeChat()
      wx.showToast({ title: '微信已绑定', icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || '绑定失败，请重试', icon: 'none' })
    }
    this.setData({ rebinding: false })
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
