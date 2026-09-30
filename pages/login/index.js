// 登录页：Mock 演示模式下提供账号快选；接入真实后端后替换为
// 微信授权登录（wx.login code + 手机号授权）+ 绑定流程
const auth = require('../../services/auth')
const { isLoggedIn } = require('../../utils/auth')

Page({
  data: {
    // 演示账号（mock/data.js employees 同源）
    demoAccounts: [
      { role: 'WORKER', name: '张伟', title: '维保人员', desc: '扫码签到 · 作业清单 · 签退上报' },
      { role: 'LEADER', name: '陈刚', title: '班组长', desc: '工单管理 · 排班确认' },
      { role: 'UNIT_ADMIN', name: '王芳', title: '使用单位安全管理员', desc: '维保记录确认 · 满意度评价' }
    ],
    logging: false
  },

  onLoad() {
    // 已登录直接进首页。onLoad 阶段本页路由尚未 routeDone，此时发起 switchTab
    // 会与当前路由竞态，触发「routeDone with a webviewId xxx is not found」，
    // 故仅记录标志，延迟到 onReady（初始路由完成）后再跳。
    // 注意：这里不走 ensureLogin 守卫——未登录时守卫会 reLaunch 到登录页自身，
    // 页面加载途中被销毁同样会触发路由竞态错误。
    this._signedIn = isLoggedIn()
  },

  onReady() {
    if (this._signedIn) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  onDemoLogin(e) {
    if (this.data.logging) return
    const index = Number(e.currentTarget.dataset.index)
    const acc = this.data.demoAccounts[index]
    this.setData({ logging: true })
    auth
      .wxLogin('', '', acc.role) // mock：按 role 返回演示账号
      .then((data) => {
        auth.applyLoginResult(data)
        getApp().setAuth(data)
        wx.reLaunch({ url: '/pages/home/index' })
      })
      .catch((err) => {
        wx.showToast({ title: err.message || '登录失败', icon: 'none' })
      })
      .then(() => this.setData({ logging: false }))
  }
})
