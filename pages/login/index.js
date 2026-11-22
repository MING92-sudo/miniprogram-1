const auth = require('../../services/auth')
const { clearUnitSession } = require('../../services/unit')
const { isLoggedIn, getRoles, getHome } = require('../../utils/auth')
const { resolveHome } = require('../../config/menus')
const nav = require('../../utils/nav')
const { TOAST_TEXT, LOGIN_TEXT, BRAND } = require('../../constants/index')

const REMEMBER_KEY = 'em_remember_login'
const WECHAT_BOUND_KEY = 'em_wechat_bound'
const CLIENT_HOME = '/pages/client/home/index'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

Page({
  data: {
    phone: '', password: '', showPassword: false,
    agreed: false, logging: false, remember: false,
    text: LOGIN_TEXT, appName: BRAND.APP_NAME, versionBadge: BRAND.VERSION_BADGE, companyFoot: BRAND.LOGIN_FOOT
  },

  onLoad(query) {
    this._redirect = (query && query.redirect) || ''
    this._pendingBindCode = ''
    this._signedIn = isLoggedIn()
    const saved = wx.getStorageSync(REMEMBER_KEY)
    if (saved && saved.phone) this.setData({ phone: saved.phone, remember: true })
  },

  onReady() {
    if (this._signedIn) setTimeout(() => wx.reLaunch({ url: getHome() || resolveHome(getRoles()) }), 300)
  },

  // 忘记密码重置成功回跳：一次性预填账号
  onShow() {
    const reset = wx.getStorageSync('em_reset_account')
    if (reset) {
      wx.removeStorageSync('em_reset_account')
      this.setData({ phone: reset })
    }
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
  forgotPassword() {
    nav.open('/pages/login/forgot')
  },
  browseAsGuest() {
    wx.navigateTo({ url: '/pages/preview/index' })
  },

  /** 统一登录结果处理（员工 / 客户同一形状）：- needSelectUnit：客户手机号在多个单位开通 → 凭 selectionToken 去选单位页； */
  enterApp(data) {
    if (data && data.needSelectUnit) {
      wx.setStorageSync('em_client_selection', {
        selectionToken: data.selectionToken,
        units: data.units,
        phone: String(this.data.phone).trim()
      })
      wx.navigateTo({ url: '/pages/client/select-unit/index' })
      return
    }
    clearUnitSession()
    auth.applyLoginResult(data)
    getApp().setAuth(data)
    wx.showToast({ title: '登录成功', icon: 'success' })
    const isClient = data && data.principalType === 'CUSTOMER'
    const target = isClient
      ? (data.home || CLIENT_HOME)
      : (nav.safeRedirect(this._redirect) || data.home || resolveHome(data.roles || [data.role]))
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
      // 不区分通道：后端按账号自动判定员工/客户
      const data = await auth.accountLogin(account, this.data.password)
      // 微信一键登录未绑定引导过来的：登录成功后用暂存 code 自动绑定（code 一次性/5 分钟，失败不阻断）
      // bindWeChat 是 needAuth 接口，须先落登录态再绑定，否则生产 401
      if (this._pendingBindCode && !(data && data.needSelectUnit)) {
        try {
          auth.applyLoginResult(data)
          await auth.bindWeChat(this._pendingBindCode)
          wx.setStorageSync(WECHAT_BOUND_KEY, true)
        } catch (e) {
          wx.showToast({ title: LOGIN_TEXT.WECHAT_BIND_FAIL, icon: 'none' })
        }
      }
      this._pendingBindCode = ''
      if (this.data.remember) wx.setStorageSync(REMEMBER_KEY, { phone: account })
      else wx.removeStorageSync(REMEMBER_KEY)
      this.enterApp(data)
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ logging: false })
  },

  // 微信一键登录（恒显）：openid 只信服务端换取；员工/客户同一入口
  async onWechatLogin() {
    if (this.data.logging) return
    if (!this.data.agreed) return wx.showToast({ title: '请先勾选同意用户协议与隐私政策', icon: 'none' })
    this.setData({ logging: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      this._lastWxCode = code
      const data = await auth.wxLogin(code)
      this.enterApp(data)
    } catch (err) {
      // 422 = openid 未绑定：引导账号密码登录，成功后自动绑定（request.js 对 401 清登录态，故后端用 422）
      if (err && err.code === 422) {
        this._pendingBindCode = this._lastWxCode || ''
        wx.showModal({ title: '微信未绑定账号', content: LOGIN_TEXT.WECHAT_UNBOUND, showCancel: false, confirmText: '知道了' })
      } else {
        wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
      }
    }
    this.setData({ logging: false })
  }
})
