// 登录页：唯一登录入口（账号密码）。员工账号=手机号；使用单位账号由管理端开通，同一表单登录，
// 后端按账号类型分流（portal=true → 门户首页）。微信绑定为可选能力，入口在「我的」页（用户需求）。
const auth = require('../../services/auth')
const { saveUnitSession, unitLoginByWechat } = require('../../services/unit')
const { isLoggedIn } = require('../../utils/auth')
const { TOAST_TEXT } = require('../../constants/index')

// 记住账号（docs/01 §10.1 3.1 / docs/03 §5.6）：仅当前设备生效。
// Minor5：密码不落盘（XOR 混淆密钥与密文同存 Storage 等价明文），只记账号。
const REMEMBER_KEY = 'em_remember_login'
// 本机记录「已在我的页开启微信绑定」：决定登录页是否出现微信一键登录入口（未开启不占位）
const WECHAT_BOUND_KEY = 'em_wechat_bound'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

Page({
  data: {
    phone: '',
    password: '',
    showPassword: false,
    agreed: false,
    logging: false,
    remember: false,
    wechatBound: false
  },

  onLoad() {
    // 已登录直接进首页。onLoad 阶段发起 switchTab 会与初始路由竞态（routeDone 错误），延迟到 onReady
    this._signedIn = isLoggedIn()
    // 记住账号：回填本机保存的账号（密码不落盘）
    const saved = wx.getStorageSync(REMEMBER_KEY)
    if (saved && saved.phone) {
      this.setData({ phone: saved.phone, remember: true })
    }
    this.setData({ wechatBound: !!wx.getStorageSync(WECHAT_BOUND_KEY) })
  },

  toggleRemember() {
    const remember = !this.data.remember
    this.setData({ remember: remember })
    // 取消勾选即清除本机保存的账号（docs/03 §5.6：不勾选不保存）
    if (!remember) wx.removeStorageSync(REMEMBER_KEY)
  },

  onReady() {
    if (this._signedIn) {
      // 延迟到路由稳定后再切换，避免 routeDone 竞态
      setTimeout(() => wx.switchTab({ url: '/pages/home/index' }), 300)
    }
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value })
  },

  onPasswordInput(e) {
    this.setData({ password: e.detail.value })
  },

  togglePassword() {
    this.setData({ showPassword: !this.data.showPassword })
  },

  toggleAgreed() {
    this.setData({ agreed: !this.data.agreed })
  },

  openAgreement() {
    wx.navigateTo({ url: '/pages/login/agreement?type=agreement' })
  },

  openPrivacy() {
    wx.navigateTo({ url: '/pages/login/agreement?type=privacy' })
  },

  // 暂不登录：进入首页以未登录状态浏览（登录规范：须提供取消/暂不登录入口，不得强制登录）
  skipLogin() {
    setTimeout(() => wx.switchTab({ url: '/pages/home/index' }), 300)
  },

  forgotPassword() {
    wx.showModal({
      title: '忘记密码',
      content: '账号由本单位管理员统一分配，请联系本单位管理员重置密码。',
      showCancel: false,
      confirmText: '知道了'
    })
  },

  // 门户身份与员工身份分开存：门户令牌进 unit_token，员工态清空，避免两套令牌混用（docs/04 A.11）
  enterPortal(data) {
    saveUnitSession(data)
    getApp().logout()
    wx.showToast({ title: '登录成功', icon: 'success' })
    setTimeout(() => wx.reLaunch({ url: '/pages/unit/home' }), 300)
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
      // 记住账号：只记账号，密码不落盘（Minor5）
      if (this.data.remember) {
        wx.setStorageSync(REMEMBER_KEY, { phone: account })
      } else {
        wx.removeStorageSync(REMEMBER_KEY)
      }
      if (data && data.portal) {
        this.setData({ logging: false })
        this.enterPortal(data)
        return
      }
      auth.applyLoginResult(data)
      getApp().setAuth(data)
      wx.showToast({ title: '登录成功', icon: 'success' })
      // 回首页 tab：switchTab 比 reLaunch 稳定（首页是 tabBar 页），延迟避开路由竞态
      setTimeout(() => wx.switchTab({ url: '/pages/home/index' }), 300)
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ logging: false })
  },

  // 微信一键登录（仅在我的页开启绑定后可用）：先按员工 openid 查账号，
  // 未命中再按使用单位绑定关系登录（/unit/login-by-wechat）
  async onWechatLogin() {
    if (this.data.logging) return
    this.setData({ logging: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      let data = null
      try {
        data = await auth.wxLogin(code)
      } catch (e) {
        // 员工目录未绑定该微信：再试使用单位绑定关系
        data = await unitLoginByWechat({ code: code })
        data.portal = true
      }
      if (data && data.portal) {
        this.setData({ logging: false })
        this.enterPortal(data)
        return
      }
      auth.applyLoginResult(data)
      getApp().setAuth(data)
      wx.showToast({ title: '登录成功', icon: 'success' })
      setTimeout(() => wx.switchTab({ url: '/pages/home/index' }), 300)
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.LOGIN_FAILED, icon: 'none' })
    }
    this.setData({ logging: false })
  }
})
