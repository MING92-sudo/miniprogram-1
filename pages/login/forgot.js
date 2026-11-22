// 忘记密码（登录页入口）：微信验证重置——code 服务端换 openid 匹配绑定账号后设新密码；
const auth = require('../../services/auth')

const RESET_ACCOUNT_KEY = 'em_reset_account'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

Page({
  data: { newPassword: '', confirmPassword: '', showPassword: false, submitting: false },

  onNewInput(e) { this.setData({ newPassword: e.detail.value }) },
  onConfirmInput(e) { this.setData({ confirmPassword: e.detail.value }) },
  togglePassword() { this.setData({ showPassword: !this.data.showPassword }) },

  async onSubmit() {
    if (this.data.submitting) return
    const pw = this.data.newPassword
    if (pw.length < 6) return wx.showToast({ title: '新密码至少 6 位', icon: 'none' })
    if (pw !== this.data.confirmPassword) return wx.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      const data = await auth.resetPasswordByWechat(code, pw)
      wx.setStorageSync(RESET_ACCOUNT_KEY, data.account || '')
      wx.showModal({
        title: '密码已重置',
        content: '旧会话已失效，请使用新密码登录。',
        showCancel: false,
        confirmText: '去登录',
        success: () => this.backToLogin()
      })
    } catch (err) {
      if (err && err.code === 422 && /未绑定/.test(err.message || '')) {
        wx.showModal({
          title: '该微信未绑定账号',
          content: '请联系本单位管理员在管理端「人员」中重置密码，再使用新密码登录。',
          showCancel: false,
          confirmText: '知道了'
        })
      } else {
        wx.showToast({ title: (err && err.message) || '重置失败，请重试', icon: 'none' })
      }
    }
    this.setData({ submitting: false })
  },

  backToLogin() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/pages/login/index' })
  }
})