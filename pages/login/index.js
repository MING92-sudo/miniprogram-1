// 登录页（M6 单账号多角色，规格 §3.2 + 屏 00）：账号/口令唯一表单，不选角色、无单位入口；
// 登录后由账号权限自动进入对应工作台（resolveHome）。微信免密为已绑定账号可选能力（R5）。
// 单账号单会话：门户 unit_token 体系已废（V41 账号统一），登录即清旧会话。品牌=博威无纸化（D4/D5）。
const auth = require('../../services/auth')
const { clearUnitSession } = require('../../services/unit')
const { isLoggedIn, getRoles, getHome } = require('../../utils/auth')
const { resolveHome } = require('../../config/menus')
const nav = require('../../utils/nav')
const { TOAST_TEXT } = require('../../constants/index')

const REMEMBER_KEY = 'em_remember_login'
const WECHAT_BOUND_KEY = 'em_wechat_bound'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

Page({
  data: {
    phone: '', password: '', showPassword: false,
    agreed: false, logging: false, remember: false, wechatBound: false
  },

  onLoad(query) {
    this._redirect = (query && query.redirect) || ''
    this._signedIn = isLoggedIn()
    const saved = wx.getStorageSync(REMEMBER_KEY)
    if (saved && saved.phone) this.setData({ phone: saved.phone, remember: true })
    this.setData({ wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY) })
  },

  onReady() {
    if (this._signedIn) setTimeout(() => wx.reLaunch({ url: getHome() || resolveHome(getRoles()) }), 300)
  },

  onPhoneInput(e) { this.setData({ phone: e.detail.value }) },
  onPasswordInput(e) { this.setData({ password: e.detail.value }) },
  togglePassword() { this.setData({ showPassword: !this.data.showPassword }) },
  toggleAgreed() { this.setData({ agreed: !this.data.agreed }) },
  toggleRemember() {
    const remember = !this.data.remember
    this.setData({ remember })
    if (!remember) wx.removeStorageSync(REMEMBER_KEY)
  },
  openAgreement() { wx.navigateTo({ url: '/pages/login/agreement?type=agreement' }) },
  openPrivacy() { wx.navigateTo({ url: '/pages/login/agreement?type=privacy' }) },
  skipLogin() { wx.navigateTo({ url: '/pages/login/agreement?type=agreement' }) },
  forgotPassword() {
    wx.showModal({ title: '忘记密码', content: '账号由本单位管理员统一分配，请联系本单位管理员重置密码。', showCancel: false, confirmText: '知道了' })
  },

  // 登录成功统一回跳：白名单 redirect 优先，否则账号默认工作台
  enterApp(data) {
    clearUnitSession()
    auth.applyLoginResult(data)
    getApp().setAuth(data)
    wx.showToast({ title: '登录成功', icon: 'success' })
    const target = nav.safeRedirect(this._redirect) || data.home || resolveHome(data.roles || [data.role])
    setTimeout(() => wx.reLaunch({ url: target }), 300)
  },

  async onSubmit() {
    if (this.data.logging) return
    const account = String(this.data.phone).trim()
    if (!account) return wx.showToast({ title: '请输入账号', icon: 'none' })
    if (!this.data.password) return wx.showToast({ title: '请输入密码', icon: 'none' })
    if (!this.data.agreed) return wx.showToast({ title: '请先勾选同意用户协议与隐私政策', icon: 'none' })
    this.setData({ logging: true })
    try {
      const data = await auth.accountLogin(account, this.data.password)
      if (this.data.remember) wx.setStorageSync(REMEMBER_KEY, { phone: account })
      else wx.removeStorageSync(REMEMBER_KEY)
      this.enterApp(data)
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ logging: false })
  },

  // 微信一键登录（仅本机已绑定后显示，R5）：openid 只信服务端换取
  async onWechatLogin() {
    if (this.data.logging) return
    if (!this.data.agreed) return wx.showToast({ title: '请先勾选同意用户协议与隐私政策', icon: 'none' })
    this.setData({ logging: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      const data = await auth.wxLogin(code)
      this.enterApp(data)
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ logging: false })
  }
})