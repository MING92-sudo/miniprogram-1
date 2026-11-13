// 使用单位个人中心：只放单位信息与设置项（绑定微信一键登录 / 修改密码 / 退出登录），
// 不含任何维保作业内容 —— 使用单位账号只应看到本单位只读数据与本页设置（用户需求）。
const {
  getUnitSession,
  isUnitLoggedIn,
  clearUnitSession,
  bindUnitWechat,
  changeUnitPassword
} = require('../../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../../constants/index')

// 本机记录「已开启微信绑定」：登录页据此显示微信一键登录入口
const WECHAT_BOUND_KEY = 'em_wechat_bound'

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

Page({
  data: {
    unitName: '',
    account: '',
    wechatBound: false,
    inviteCode: '',
    binding: false,
    oldPassword: '',
    newPassword: '',
    confirmPassword: '',
    saving: false,
    versionText: '',
    text: UNIT_PORTAL_TEXT
  },

  onLoad() {
    // 门户令牌缺失/过期 → 统一登录页（门户无独立登录页）
    if (!isUnitLoggedIn()) {
      wx.reLaunch({ url: '/pages/login/index' })
      return
    }
    const session = getUnitSession() || {}
    this.setData({
      unitName: session.unitName || '',
      account: session.account || '',
      wechatBound: !!session.wechatBound
    })
    try {
      const mp = wx.getAccountInfoSync().miniProgram
      this.setData({
        versionText: mp.version ? '版本 ' + mp.version : mp.envVersion === 'trial' ? '体验版' : '开发版'
      })
    } catch (e) {
      this.setData({ versionText: '' })
    }
  },

  goPortal() {
    wx.navigateTo({ url: '/pages/unit/home' })
  },

  onInviteInput(e) {
    this.setData({ inviteCode: e.detail.value })
  },

  onOldPasswordInput(e) {
    this.setData({ oldPassword: e.detail.value })
  },

  onNewPasswordInput(e) {
    this.setData({ newPassword: e.detail.value })
  },

  onConfirmPasswordInput(e) {
    this.setData({ confirmPassword: e.detail.value })
  },

  // 绑定微信：邀请码 + wx.login code（绑定后可用微信免密登录，属于可选设置项）
  async onBindWechat() {
    if (this.data.binding) return
    const inviteCode = String(this.data.inviteCode || '').trim()
    if (!inviteCode) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.INVITE_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ binding: true })
    try {
      const code = await wxLoginCode()
      if (!code) throw new Error('微信授权失败，请重试')
      await bindUnitWechat({ inviteCode: inviteCode, code: code })
      const session = getUnitSession() || {}
      session.wechatBound = true
      wx.setStorageSync('unit_info', session)
      wx.setStorageSync(WECHAT_BOUND_KEY, true)
      this.setData({ wechatBound: true, inviteCode: '' })
      wx.showToast({ title: UNIT_PORTAL_TEXT.BIND_SUCCESS, icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ binding: false })
  },

  // 修改口令：旧口令校验由后端负责（422 原口令不正确）
  async onChangePassword() {
    if (this.data.saving) return
    const oldPassword = String(this.data.oldPassword || '')
    const newPassword = String(this.data.newPassword || '')
    if (!oldPassword) return wx.showToast({ title: '请输入原口令', icon: 'none' })
    if (newPassword.length < 6) return wx.showToast({ title: '新口令至少 6 位', icon: 'none' })
    if (newPassword !== String(this.data.confirmPassword || '')) {
      return wx.showToast({ title: '两次输入的新口令不一致', icon: 'none' })
    }
    if (newPassword === oldPassword) return wx.showToast({ title: '新口令不能与原口令相同', icon: 'none' })
    this.setData({ saving: true })
    try {
      await changeUnitPassword({ oldPassword: oldPassword, newPassword: newPassword })
      this.setData({ oldPassword: '', newPassword: '', confirmPassword: '' })
      wx.showModal({
        title: '口令已修改',
        content: '下次登录请使用新口令。',
        showCancel: false,
        confirmText: '知道了'
      })
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ saving: false })
  },

  onLogout() {
    wx.showModal({
      title: UNIT_PORTAL_TEXT.LOGOUT_CONFIRM_TITLE,
      content: UNIT_PORTAL_TEXT.LOGOUT_CONFIRM_CONTENT,
      success: (res) => {
        if (!res.confirm) return
        clearUnitSession()
        wx.reLaunch({ url: '/pages/login/index' })
      }
    })
  }
})
