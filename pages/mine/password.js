// 修改密码（用户需求④：随机初始密码首次登录后自助修改）
const auth = require('../../services/auth')
const { ensureLogin } = require('../../utils/guard')

Page({
  data: { oldPassword: '', newPassword: '', confirm: '', submitting: false },

  onLoad() {
    ensureLogin()
  },

  onOld(e) { this.setData({ oldPassword: e.detail.value }) },
  onNew(e) { this.setData({ newPassword: e.detail.value }) },
  onConfirm(e) { this.setData({ confirm: e.detail.value }) },

  submit() {
    const { oldPassword, newPassword, confirm, submitting } = this.data
    if (submitting) return
    if (!oldPassword || !newPassword) {
      wx.showToast({ title: '请填写原密码与新密码', icon: 'none' })
      return
    }
    if (newPassword.length < 6) {
      wx.showToast({ title: '新密码至少 6 位', icon: 'none' })
      return
    }
    if (newPassword !== confirm) {
      wx.showToast({ title: '两次新密码不一致', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    auth.changePassword(oldPassword, newPassword)
      .then(() => {
        wx.showToast({ title: '修改成功，请牢记新密码', icon: 'none' })
        setTimeout(() => wx.navigateBack(), 1200)
      })
      .catch((e) => {
        wx.showToast({ title: (e && e.message) || '修改失败', icon: 'none' })
      })
      .finally(() => this.setData({ submitting: false }))
  }
})