// 使用单位门户登录（W3，docs/04 A.11）：账号口令 → 门户令牌（独立存 unit_token，与员工登录态互不影响）；
// 可选邀请码绑定微信（openid 只由服务端用 wx.login code 换取，AGENTS §2.3）。
const { unitLogin, unitLoginByWechat, bindUnitWechat, saveUnitSession, isUnitLoggedIn } = require('../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../constants/index')

Page({
  data: {
    account: '',
    password: '',
    inviteCode: '',
    submitting: false,
    binding: false,
    wxLogging: false,
    text: UNIT_PORTAL_TEXT
  },

  onLoad() {
    // 已登录直接进门户首页，避免重复登录
    if (isUnitLoggedIn()) wx.reLaunch({ url: '/pages/unit/home' })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!field) return
    const patch = {}
    patch[field] = e.detail.value
    this.setData(patch)
  },

  async onLogin() {
    const d = this.data
    if (!d.account.trim()) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.ACCOUNT_REQUIRED, icon: 'none' })
      return
    }
    if (!d.password) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.PASSWORD_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const res = await unitLogin({ account: d.account.trim(), password: d.password })
      saveUnitSession(res)
      wx.showToast({ title: UNIT_PORTAL_TEXT.LOGIN_SUCCESS, icon: 'success' })
      wx.reLaunch({ url: '/pages/unit/home' })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  // 微信一键登录：已绑过邀请码的账号免密进入（code → 后端换 openid → 反查单位）
  onWechatLogin() {
    if (this.data.wxLogging) return
    this.setData({ wxLogging: true })
    wx.login({
      success: async (res) => {
        if (!res.code) {
          this.setData({ wxLogging: false })
          wx.showToast({ title: TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
          return
        }
        try {
          const data = await unitLoginByWechat({ code: res.code })
          saveUnitSession(data)
          wx.showToast({ title: UNIT_PORTAL_TEXT.LOGIN_SUCCESS, icon: 'success' })
          wx.reLaunch({ url: '/pages/unit/home' })
        } catch (e) {
          wx.showToast({ title: e.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
        }
        this.setData({ wxLogging: false })
      },
      fail: () => {
        this.setData({ wxLogging: false })
        wx.showToast({ title: TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
      }
    })
  },

  // 绑定微信：邀请码 + wx.login code（绑定后可在微信内识别本机，无需额外口令）
  onBind() {
    const inviteCode = (this.data.inviteCode || '').trim()
    if (!inviteCode) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.INVITE_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ binding: true })
    wx.login({
      success: async (res) => {
        if (!res.code) {
          this.setData({ binding: false })
          wx.showToast({ title: TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
          return
        }
        try {
          await bindUnitWechat({ inviteCode: inviteCode, code: res.code })
          wx.showToast({ title: UNIT_PORTAL_TEXT.BIND_SUCCESS, icon: 'success' })
        } catch (e) {
          wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
        }
        this.setData({ binding: false })
      },
      fail: () => {
        this.setData({ binding: false })
        wx.showToast({ title: TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
      }
    })
  }
})
