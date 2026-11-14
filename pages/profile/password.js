// 修改密码（M2 迁自 pages/mine/password，深色壳）：原口令 + 新口令≥6 位 + 确认一致。
const auth = require('../../services/auth')
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const nav = require('../../utils/nav')

definePage(ROUTES['/pages/profile/password'], {
  data: { oldPassword: '', newPassword: '', confirm: '', submitting: false },
  onOld(e) { this.setData({ oldPassword: e.detail.value }) },
  onNew(e) { this.setData({ newPassword: e.detail.value }) },
  onConfirm(e) { this.setData({ confirm: e.detail.value }) },
  onBack() { nav.back() },
  submit() {
    const { oldPassword, newPassword, confirm, submitting } = this.data
    if (submitting) return
    if (!oldPassword || !newPassword) return wx.showToast({ title: '请填写原密码与新密码', icon: 'none' })
    if (newPassword.length < 6) return wx.showToast({ title: '新密码至少 6 位', icon: 'none' })
    if (newPassword !== confirm) return wx.showToast({ title: '两次新密码不一致', icon: 'none' })
    this.setData({ submitting: true })
    auth.changePassword(oldPassword, newPassword)
      .then(() => {
        wx.showToast({ title: '修改成功，请牢记新密码', icon: 'none' })
        setTimeout(() => nav.back(), 1200)
      })
      .catch((e) => wx.showToast({ title: (e && e.message) || '修改失败', icon: 'none' }))
      .finally(() => this.setData({ submitting: false }))
  }
})