// 使用单位门户首页（W3，docs/04 A.11）：本单位电梯数 / 待签字记录数 / 未闭环急修数 + 三个查看入口 + 报修。
// 门户身份来自 unit_token（与员工登录态分开存），数据域一律由后端按 useUnitId 收敛。
// 微信免密绑定入口不在此页，统一放「我的」页（用户需求：由账号本人自行选择是否开启）
const {
  getUnitElevators,
  getUnitPendingSigns,
  getUnitFaults,
  getUnitSession,
  isUnitLoggedIn,
  clearUnitSession
} = require('../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../constants/index')

const LOGIN_PAGE = '/pages/login/index'

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
    // 令牌缺失/已过期 → 回统一登录页（门户账号也走同一表单，后端按账号类型分流）
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

  // 门户个人中心：单位信息 + 设置项（绑定微信 / 改口令 / 退出），不含维保内容
  goMine() {
    wx.navigateTo({ url: '/pages/unit/mine/index' })
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
