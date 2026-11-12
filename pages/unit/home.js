// 使用单位门户首页（W3，docs/04 A.11）：本单位电梯数 / 待签字记录数 / 未闭环急修数 + 三个查看入口 + 报修。
// 门户身份来自 unit_token（与员工登录态分开存），数据域一律由后端按 useUnitId 收敛。
const {
  getUnitElevators,
  getUnitPendingSigns,
  getUnitFaults,
  getUnitSession,
  isUnitLoggedIn,
  clearUnitSession
} = require('../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../constants/index')

const LOGIN_PAGE = '/pages/unit/login'

Page({
  data: {
    unitName: '',
    elevatorCount: 0,
    pendingSignCount: 0,
    openFaultCount: 0,
    loading: true,
    failed: false,
    text: UNIT_PORTAL_TEXT
  },

  onShow() {
    // 令牌缺失/已过期 → 回门户登录（request.js 的 401 分支会跳员工登录，门户无入口，故本地先拦）
    if (!isUnitLoggedIn()) {
      wx.reLaunch({ url: LOGIN_PAGE })
      return
    }
    const session = getUnitSession() || {}
    this.setData({ unitName: session.unitName || '' })
    this.fetch()
  },

  async fetch() {
    this.setData({ loading: true, failed: false })
    let failed = false
    const elevators = await getUnitElevators().catch(function () {
      failed = true
      return []
    })
    const signs = await getUnitPendingSigns().catch(function () {
      failed = true
      return []
    })
    const faults = await getUnitFaults().catch(function () {
      failed = true
      return []
    })
    const openFaults = (faults || []).filter(function (f) {
      return f.status !== 'CLOSED'
    })
    this.setData({
      elevatorCount: (elevators || []).length,
      pendingSignCount: (signs || []).length,
      openFaultCount: openFaults.length,
      loading: false,
      failed: failed
    })
    if (failed) wx.showToast({ title: TOAST_TEXT.LOAD_FAILED, icon: 'none' })
  },

  goElevators() {
    wx.navigateTo({ url: '/pages/unit/elevators' })
  },

  goRecords() {
    wx.navigateTo({ url: '/pages/unit/records' })
  },

  goFaults() {
    wx.navigateTo({ url: '/pages/unit/faults' })
  },

  goReport() {
    wx.navigateTo({ url: '/pages/unit/report' })
  },

  onRetry() {
    this.fetch()
  },

  onLogout() {
    wx.showModal({
      title: UNIT_PORTAL_TEXT.LOGOUT_CONFIRM_TITLE,
      content: UNIT_PORTAL_TEXT.LOGOUT_CONFIRM_CONTENT,
      success: (res) => {
        if (!res.confirm) return
        clearUnitSession()
        wx.reLaunch({ url: LOGIN_PAGE })
      }
    })
  }
})
