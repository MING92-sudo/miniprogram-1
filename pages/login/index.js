// 登录页：账号密码登录（账号由维保单位系统分配，手机号为账号）
// 登录成功后绑定微信（wx.login → /auth/bind-wechat），下次可微信一键登录（P2）
const auth = require('../../services/auth')
const { isLoggedIn } = require('../../utils/auth')
const { bindWeChat } = require('../../utils/bind-wechat')

Page({
  data: {
    phone: '',
    password: '',
    showPassword: false,
    agreed: false,
    logging: false
  },

  onLoad() {
    // 已登录直接进首页。onLoad 阶段发起 switchTab 会与初始路由竞态（routeDone 错误），延迟到 onReady
    this._signedIn = isLoggedIn()
  },

  onReady() {
    if (this._signedIn) {
      wx.switchTab({ url: '/pages/home/index' })
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

  forgotPassword() {
    wx.showModal({
      title: '忘记密码',
      content: '账号由维保单位系统统一分配，请联系本单位管理员重置密码。',
      showCancel: false,
      confirmText: '知道了'
    })
  },

  async onSubmit() {
    if (this.data.logging) return
    const phone = String(this.data.phone).trim()
    if (!/^1\d{10}$/.test(phone)) return wx.showToast({ title: '请输入 11 位手机号账号', icon: 'none' })
    if (!this.data.password) return wx.showToast({ title: '请输入密码', icon: 'none' })
    if (!this.data.agreed) return wx.showToast({ title: '请先勾选同意用户协议与隐私政策', icon: 'none' })
    this.setData({ logging: true })
    try {
      const data = await auth.accountLogin(phone, this.data.password)
      auth.applyLoginResult(data)
      getApp().setAuth(data)
      // 登录成功后绑定微信，下次可微信一键登录；失败可在「我的」重新绑定
      try {
        await bindWeChat()
      } catch (e2) {
        console.warn('[login] 微信绑定失败:', e2 && e2.message)
      }
      wx.showToast({ title: '登录成功', icon: 'success' })
      wx.reLaunch({ url: '/pages/home/index' })
    } catch (err) {
      wx.showToast({ title: err.message || '登录失败', icon: 'none' })
    }
    this.setData({ logging: false })
  }
})
