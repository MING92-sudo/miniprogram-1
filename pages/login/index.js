// 登录页：账号密码登录（账号由维保单位系统分配，手机号为账号）
// 登录成功后绑定微信（wx.login → /auth/bind-wechat），下次可微信一键登录（P2）
const auth = require('../../services/auth')
const { isLoggedIn } = require('../../utils/auth')
const { uuid } = require('../../utils/util')

// 记住密码（docs/01 §10.1 3.1 / docs/03 §5.6）：仅当前设备生效。
// 小程序无系统级密钥库，这里用「安装期随机设备密钥 + XOR + 十六进制」避免明文落盘，
// 属本机混淆而非强加密：清除缓存/换设备即失效，密钥与密文都只存本机 Storage（不上传）。
const REMEMBER_KEY = 'em_remember_login'
const DEVICE_KEY = 'em_device_key'

function deviceKey() {
  let k = wx.getStorageSync(DEVICE_KEY)
  if (!k) {
    k = uuid()
    wx.setStorageSync(DEVICE_KEY, k)
  }
  return k
}

function obfuscate(text) {
  const k = deviceKey()
  let hex = ''
  for (let i = 0; i < text.length; i++) {
    hex += ('0000' + (text.charCodeAt(i) ^ k.charCodeAt(i % k.length)).toString(16)).slice(-4)
  }
  return hex
}

function deobfuscate(hex) {
  const k = deviceKey()
  let out = ''
  for (let i = 0; i + 4 <= String(hex).length; i += 4) {
    out += String.fromCharCode(parseInt(hex.slice(i, i + 4), 16) ^ k.charCodeAt((i / 4) % k.length))
  }
  return out
}

Page({
  data: {
    phone: '',
    password: '',
    showPassword: false,
    agreed: false,
    logging: false,
    remember: false
  },

  onLoad() {
    // 已登录直接进首页。onLoad 阶段发起 switchTab 会与初始路由竞态（routeDone 错误），延迟到 onReady
    this._signedIn = isLoggedIn()
    // 记住密码：回填本机保存的账号与密码（混淆存储，仅当前设备）
    const saved = wx.getStorageSync(REMEMBER_KEY)
    if (saved && saved.phone) {
      let password = ''
      try {
        password = deobfuscate(saved.pwd || '')
      } catch (e) {
        password = ''
      }
      this.setData({ phone: saved.phone, password: password, remember: true })
    }
  },

  toggleRemember() {
    const remember = !this.data.remember
    this.setData({ remember: remember })
    // 取消勾选即清除本机保存的账号密码（docs/03 §5.6：不勾选不保存）
    if (!remember) wx.removeStorageSync(REMEMBER_KEY)
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
      // 登录成功后再落盘：避免记住无效凭据
      if (this.data.remember) {
        wx.setStorageSync(REMEMBER_KEY, { phone: phone, pwd: obfuscate(this.data.password) })
      } else {
        wx.removeStorageSync(REMEMBER_KEY)
      }
      // 登录成功后绑定微信：wx.login 取 code 与账号关联（下次可微信一键登录，P2）
      try {
        const code = await new Promise((resolve, reject) => {
          wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
        })
        if (code) await auth.bindWeChat(code)
      } catch (e2) {
        // 微信绑定失败不阻断登录，可稍后重试
      }
      wx.showToast({ title: '登录成功', icon: 'success' })
      wx.reLaunch({ url: '/pages/home/index' })
    } catch (err) {
      wx.showToast({ title: err.message || '登录失败', icon: 'none' })
    }
    this.setData({ logging: false })
  }
})
