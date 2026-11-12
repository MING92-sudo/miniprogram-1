// 本单位急修记录（W3，docs/04 A.11 GET /unit/faults）：只读，展示报修时间、故障描述与闭环状态。
const { getUnitFaults, isUnitLoggedIn } = require('../../services/unit')
const { UNIT_PORTAL_TEXT, UNIT_FAULT_STATUS_TEXT, TOAST_TEXT } = require('../../constants/index')

function decorate(f) {
  const status = f.status || 'OPEN'
  return Object.assign({}, f, {
    statusText: UNIT_FAULT_STATUS_TEXT[status] || status,
    closed: status === 'CLOSED'
  })
}

Page({
  data: {
    list: [],
    loading: true,
    failed: false,
    text: UNIT_PORTAL_TEXT
  },

  onShow() {
    if (!isUnitLoggedIn()) {
      wx.reLaunch({ url: '/pages/unit/login' })
      return
    }
    this.fetch()
  },

  async fetch() {
    this.setData({ loading: true, failed: false })
    try {
      const list = await getUnitFaults()
      this.setData({ list: (list || []).map(decorate), loading: false })
    } catch (e) {
      this.setData({ loading: false, failed: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onRetry() {
    this.fetch()
  },

  goReport() {
    wx.navigateTo({ url: '/pages/unit/report' })
  }
})
