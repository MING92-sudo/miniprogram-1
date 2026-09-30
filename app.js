const { getToken, getUserInfo, getRole, clearAuth } = require('./utils/auth')

App({
  globalData: {
    token: '',
    userInfo: null,
    role: ''
  },

  onLaunch() {
    this.restoreAuth()
    this.watchNetwork()
  },

  // 网络恢复时自动补传离线队列（docs/02 离线策略）
  watchNetwork() {
    wx.onNetworkStatusChange((res) => {
      if (!res.isConnected) return
      // 未登录不触发补传（避免无 token 请求引发 401 连锁跳转）
      if (!this.globalData.token) return
      const { flushQueue } = require('./utils/offline')
      flushQueue().then((r) => {
        if (r.success > 0) {
          wx.showToast({ title: `已自动补传 ${r.success} 条离线数据`, icon: 'none' })
        }
      })
    })
  },

  // 从本地存储恢复登录态
  restoreAuth() {
    this.globalData.token = getToken() || ''
    this.globalData.userInfo = getUserInfo() || null
    this.globalData.role = getRole() || ''
  },

  // 登录成功后写入全局态
  setAuth({ token, userInfo, role }) {
    this.globalData.token = token
    this.globalData.userInfo = userInfo
    this.globalData.role = role
  },

  // 退出登录 / 401 时清空
  logout() {
    clearAuth()
    this.globalData.token = ''
    this.globalData.userInfo = null
    this.globalData.role = ''
  }
})
