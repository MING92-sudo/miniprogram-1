// 本单位电梯清单（W3，docs/04 A.11 GET /unit/elevators）：只读，仅展示本 useUnitId 名下电梯。
const { getUnitElevators, isUnitLoggedIn } = require('../../services/unit')
const { UNIT_PORTAL_TEXT, TOAST_TEXT } = require('../../constants/index')

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
      const list = await getUnitElevators()
      this.setData({ list: list || [], loading: false })
    } catch (e) {
      this.setData({ loading: false, failed: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onRetry() {
    this.fetch()
  }
})
